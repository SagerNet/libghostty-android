#include <jni.h>
#include <android/keycodes.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <utility>
#include <vector>

#include <ghostty/vt.h>

namespace {

constexpr const char* kLogTag = "ghostty_android";

constexpr uint16_t kFlagBold = 1 << 0;
constexpr uint16_t kFlagItalic = 1 << 1;
constexpr uint16_t kFlagFaint = 1 << 2;
constexpr uint16_t kFlagUnderline = 1 << 3;
constexpr uint16_t kFlagStrikethrough = 1 << 4;
constexpr uint16_t kFlagInverse = 1 << 5;
constexpr uint16_t kFlagInvisible = 1 << 6;
constexpr uint16_t kFlagWide = 1 << 7;
constexpr uint16_t kFlagSpacer = 1 << 8;
constexpr uint16_t kFlagFgDefault = 1 << 9;
constexpr uint16_t kFlagBgNone = 1 << 10;

constexpr size_t kHeaderBytes = 15 * sizeof(int32_t);
constexpr size_t kRowHeaderBytes = 4 * sizeof(int32_t);
constexpr size_t kCellRecordBytes = 16;
static_assert(kHeaderBytes == 60, "header size drifted from the Kotlin reader");
static_assert(kCellRecordBytes == 2 * sizeof(int32_t) + 4 * sizeof(uint16_t),
              "cell record size drifted from its field layout");
constexpr uint32_t kMaxCellTextUnits = 32;

constexpr jint kEventBell = 1;
constexpr jint kEventTitle = 1 << 1;
constexpr jint kEventClipboard = 1 << 2;
constexpr jint kEventPwd = 1 << 3;
constexpr jint kEventNotification = 1 << 4;
constexpr jint kEventProgress = 1 << 5;
constexpr jint kEventClipboardRead = 1 << 6;

constexpr uint32_t kKittyPlaceholder = 0x10EEEE;

constexpr auto kSyncOutputTimeout = std::chrono::milliseconds(1000);

// Without libc, libghostty's default allocator on Android is Zig's page
// allocator (patches/ghostty/0002): every allocation rounds up to a page.
void* mallocAlloc(void*, size_t len, uint8_t alignment, uintptr_t) {
  void* ptr = nullptr;
  const size_t align = alignment < sizeof(void*) ? sizeof(void*) : alignment;
  if (posix_memalign(&ptr, align, len) != 0) return nullptr;
  return ptr;
}

bool mallocResize(void*, void*, size_t memoryLen, uint8_t, size_t newLen, uintptr_t) {
  return newLen <= memoryLen;
}

void* mallocRemap(void*, void*, size_t, uint8_t, size_t, uintptr_t) {
  return nullptr;
}

void mallocFree(void*, void* memory, size_t, uint8_t, uintptr_t) {
  free(memory);
}

constexpr GhosttyAllocatorVtable kMallocVtable = {
    &mallocAlloc, &mallocResize, &mallocRemap, &mallocFree};
constexpr GhosttyAllocator kMallocAllocator = {nullptr, &kMallocVtable};

JavaVM* gJavaVm = nullptr;
jclass gBitmapFactoryClass = nullptr;
jmethodID gDecodeByteArray = nullptr;
jclass gBitmapClass = nullptr;
jmethodID gBitmapGetWidth = nullptr;
jmethodID gBitmapGetHeight = nullptr;
jmethodID gBitmapGetPixels = nullptr;
jmethodID gBitmapRecycle = nullptr;

constexpr int64_t kMaxImagePixels = 100 * 1000 * 1000;

bool decodePngCallback(void*, const GhosttyAllocator* allocator, const uint8_t* data,
                       size_t dataLen, GhosttySysImage* out) {
  if (gJavaVm == nullptr || data == nullptr || dataLen == 0) return false;
  JNIEnv* env = nullptr;
  if (gJavaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return false;
  if (env->PushLocalFrame(8) != 0) return false;

  bool decoded = false;
  do {
    jbyteArray pngArray = env->NewByteArray(static_cast<jsize>(dataLen));
    if (pngArray == nullptr) break;
    env->SetByteArrayRegion(pngArray, 0, static_cast<jsize>(dataLen),
                            reinterpret_cast<const jbyte*>(data));
    jobject bitmap = env->CallStaticObjectMethod(gBitmapFactoryClass, gDecodeByteArray,
                                                 pngArray, 0, static_cast<jint>(dataLen));
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      break;
    }
    if (bitmap == nullptr) break;
    const jint width = env->CallIntMethod(bitmap, gBitmapGetWidth);
    const jint height = env->CallIntMethod(bitmap, gBitmapGetHeight);
    if (width <= 0 || height <= 0 ||
        static_cast<int64_t>(width) * height > kMaxImagePixels) {
      break;
    }
    const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
    jintArray pixelArray = env->NewIntArray(static_cast<jsize>(pixelCount));
    if (pixelArray == nullptr) break;
    env->CallVoidMethod(bitmap, gBitmapGetPixels, pixelArray, 0, width, 0, 0, width, height);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      break;
    }
    env->CallVoidMethod(bitmap, gBitmapRecycle);

    const size_t outLen = pixelCount * 4;
    uint8_t* outData = ghostty_alloc(allocator, outLen);
    if (outData == nullptr) break;
    auto* pixels = static_cast<jint*>(env->GetPrimitiveArrayCritical(pixelArray, nullptr));
    if (pixels == nullptr) {
      ghostty_free(allocator, outData, outLen);
      break;
    }
    for (size_t i = 0; i < pixelCount; i++) {
      const auto argbPixel = static_cast<uint32_t>(pixels[i]);
      outData[i * 4] = (argbPixel >> 16) & 0xFF;
      outData[i * 4 + 1] = (argbPixel >> 8) & 0xFF;
      outData[i * 4 + 2] = argbPixel & 0xFF;
      outData[i * 4 + 3] = argbPixel >> 24;
    }
    env->ReleasePrimitiveArrayCritical(pixelArray, pixels, JNI_ABORT);

    out->width = static_cast<uint32_t>(width);
    out->height = static_cast<uint32_t>(height);
    out->data = outData;
    out->data_len = outLen;
    decoded = true;
  } while (false);

  env->PopLocalFrame(nullptr);
  return decoded;
}

struct Session {
  GhosttyTerminal term = nullptr;
  GhosttyRenderState renderState = nullptr;
  GhosttyRenderStateRowIterator rowIter = nullptr;
  GhosttyRenderStateRowCells cells = nullptr;
  GhosttyKeyEncoder encoder = nullptr;
  GhosttyKeyEvent keyEvent = nullptr;
  GhosttyMouseEncoder mouseEncoder = nullptr;
  GhosttyMouseEvent mouseEvent = nullptr;
  GhosttyKittyGraphicsPlacementIterator kittyIterator = nullptr;
  GhosttyKittyGraphicsVirtualPlacementIterator kittyVirtualIterator = nullptr;
  uint16_t cols = 0;
  uint16_t rows = 0;
  uint32_t cellWidthPx = 0;
  uint32_t cellHeightPx = 0;
  bool mouseButtonDown = false;
  std::vector<uint8_t> ptyOut;
  jint pendingEvents = 0;
  std::string clipboardText;
  std::string xtversion;
  std::deque<std::pair<std::string, std::string>> notifications;
  int32_t progressState = 0;
  int32_t progressPercent = -1;
  bool colorSchemeDark = true;
  int32_t clipboardReadLocation = -1;
  bool syncOutputActive = false;
  std::chrono::steady_clock::time_point syncOutputSince{};
};

void writePtyCallback(GhosttyTerminal, void* userdata, const uint8_t* data, size_t len) {
  auto* session = static_cast<Session*>(userdata);
  session->ptyOut.insert(session->ptyOut.end(), data, data + len);
}

void bellCallback(GhosttyTerminal, void* userdata) {
  static_cast<Session*>(userdata)->pendingEvents |= kEventBell;
}

bool sizeCallback(GhosttyTerminal, void* userdata, GhosttySizeReportSize* out) {
  auto* session = static_cast<Session*>(userdata);
  if (session->cellWidthPx == 0 || session->cellHeightPx == 0) return false;
  out->rows = session->rows;
  out->columns = session->cols;
  out->cell_width = session->cellWidthPx;
  out->cell_height = session->cellHeightPx;
  return true;
}

// The attributes ghostty itself reports (termio/stream_handler.zig): DA1
// "CSI ? 62 ; 22 c", DA2 "CSI > 1 ; 10 ; 0 c".
bool deviceAttributesCallback(GhosttyTerminal, void*, GhosttyDeviceAttributes* out) {
  out->primary.conformance_level = GHOSTTY_DA_CONFORMANCE_VT220;
  out->primary.features[0] = GHOSTTY_DA_FEATURE_ANSI_COLOR;
  out->primary.num_features = 1;
  out->secondary.device_type = GHOSTTY_DA_DEVICE_TYPE_VT220;
  out->secondary.firmware_version = 10;
  out->secondary.rom_cartridge = 0;
  out->tertiary.unit_id = 0;
  return true;
}

void titleChangedCallback(GhosttyTerminal, void* userdata) {
  static_cast<Session*>(userdata)->pendingEvents |= kEventTitle;
}

void pwdChangedCallback(GhosttyTerminal, void* userdata) {
  static_cast<Session*>(userdata)->pendingEvents |= kEventPwd;
}

void desktopNotificationCallback(GhosttyTerminal, void* userdata,
                                 const GhosttyTerminalDesktopNotification* notification) {
  auto* session = static_cast<Session*>(userdata);
  if (session->notifications.size() >= 16) return;
  session->notifications.emplace_back(
      std::string(reinterpret_cast<const char*>(notification->title.ptr),
                  notification->title.len),
      std::string(reinterpret_cast<const char*>(notification->body.ptr),
                  notification->body.len));
  session->pendingEvents |= kEventNotification;
}

void progressReportCallback(GhosttyTerminal, void* userdata,
                            const GhosttyTerminalProgressReport* report) {
  auto* session = static_cast<Session*>(userdata);
  session->progressState = static_cast<int32_t>(report->state);
  session->progressPercent = report->progress;
  session->pendingEvents |= kEventProgress;
}

void clipboardReadCallback(GhosttyTerminal, void* userdata, GhosttyClipboardLocation location) {
  auto* session = static_cast<Session*>(userdata);
  session->clipboardReadLocation = static_cast<int32_t>(location);
  session->pendingEvents |= kEventClipboardRead;
}

bool colorSchemeCallback(GhosttyTerminal, void* userdata, GhosttyColorScheme* out) {
  *out = static_cast<Session*>(userdata)->colorSchemeDark ? GHOSTTY_COLOR_SCHEME_DARK
                                                          : GHOSTTY_COLOR_SCHEME_LIGHT;
  return true;
}

GhosttyString xtversionCallback(GhosttyTerminal, void* userdata) {
  auto* session = static_cast<Session*>(userdata);
  GhosttyString version{};
  version.ptr = reinterpret_cast<const uint8_t*>(session->xtversion.data());
  version.len = session->xtversion.size();
  return version;
}

GhosttyClipboardWriteResult clipboardWriteCallback(GhosttyTerminal, void* userdata,
                                                   const GhosttyClipboardWrite* write) {
  auto* session = static_cast<Session*>(userdata);
  if (write->location != GHOSTTY_CLIPBOARD_LOCATION_STANDARD) {
    return GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
  }
  if (write->contents_len == 0) {
    session->clipboardText.clear();
    session->pendingEvents |= kEventClipboard;
    return GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
  }
  for (size_t i = 0; i < write->contents_len; i++) {
    const GhosttyClipboardContent& content = write->contents[i];
    if (content.mime.len >= 5 && memcmp(content.mime.ptr, "text/", 5) == 0) {
      session->clipboardText.assign(reinterpret_cast<const char*>(content.data.ptr),
                                    content.data.len);
      session->pendingEvents |= kEventClipboard;
      return GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
    }
  }
  return GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
}

jbyteArray stringData(JNIEnv* env, Session* session, GhosttyTerminalData key) {
  GhosttyString str{};
  if (ghostty_terminal_get(session->term, key, &str) != GHOSTTY_SUCCESS || str.len == 0) {
    return nullptr;
  }
  jbyteArray out = env->NewByteArray(static_cast<jsize>(str.len));
  if (out == nullptr) return nullptr;
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(str.len),
                          reinterpret_cast<const jbyte*>(str.ptr));
  return out;
}

Session* fromHandle(jlong handle) {
  return reinterpret_cast<Session*>(static_cast<intptr_t>(handle));
}

bool viewportGridRef(Session* session, jint col, jint row, GhosttyGridRef* out) {
  GhosttyPoint point{};
  point.tag = GHOSTTY_POINT_TAG_VIEWPORT;
  point.value.coordinate.x = static_cast<uint16_t>(col);
  point.value.coordinate.y = static_cast<uint32_t>(row);
  return ghostty_terminal_grid_ref(session->term, point, out) == GHOSTTY_SUCCESS;
}

// ghostty's Screen.select does not mark the selected rows dirty.
void markRenderDirtyFull(Session* session) {
  GhosttyRenderStateDirty full = GHOSTTY_RENDER_STATE_DIRTY_FULL;
  ghostty_render_state_set(session->renderState, GHOSTTY_RENDER_STATE_OPTION_DIRTY, &full);
}

bool installSelection(Session* session, const GhosttySelection* selection) {
  if (ghostty_terminal_set(session->term, GHOSTTY_TERMINAL_OPT_SELECTION, selection) !=
      GHOSTTY_SUCCESS) {
    return false;
  }
  markRenderDirtyFull(session);
  return true;
}

int32_t argb(GhosttyColorRgb color) {
  return static_cast<int32_t>(0xFF000000u | (static_cast<uint32_t>(color.r) << 16) |
                              (static_cast<uint32_t>(color.g) << 8) | color.b);
}

bool parseColor(JNIEnv* env, jstring str, GhosttyColorRgb* out) {
  if (str == nullptr) return false;
  const char* utf = env->GetStringUTFChars(str, nullptr);
  if (utf == nullptr) return false;
  const bool valid = ghostty_color_parse(utf, strlen(utf), out) == GHOSTTY_SUCCESS;
  if (!valid) {
    __android_log_print(ANDROID_LOG_VERBOSE, kLogTag, "ignoring invalid theme color: %s", utf);
  }
  env->ReleaseStringUTFChars(str, utf);
  return valid;
}

bool modeSet(Session* session, GhosttyMode mode) {
  GhosttyTerminalModeConfig config{};
  config.mode = mode;
  if (ghostty_terminal_get(session->term, GHOSTTY_TERMINAL_DATA_MODE, &config) !=
      GHOSTTY_SUCCESS) {
    return false;
  }
  return config.value;
}

jbyteArray bytesToArray(JNIEnv* env, const char* data, size_t len) {
  jbyteArray out = env->NewByteArray(static_cast<jsize>(len));
  if (out == nullptr) return nullptr;
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(len),
                          reinterpret_cast<const jbyte*>(data));
  return out;
}

void destroySession(Session* session) {
  if (session == nullptr) return;
  ghostty_kitty_graphics_virtual_placement_iterator_free(session->kittyVirtualIterator);
  ghostty_kitty_graphics_placement_iterator_free(session->kittyIterator);
  ghostty_mouse_event_free(session->mouseEvent);
  ghostty_mouse_encoder_free(session->mouseEncoder);
  ghostty_key_event_free(session->keyEvent);
  ghostty_key_encoder_free(session->encoder);
  ghostty_render_state_row_cells_free(session->cells);
  ghostty_render_state_row_iterator_free(session->rowIter);
  ghostty_render_state_free(session->renderState);
  ghostty_terminal_free(session->term);
  delete session;
}

GhosttyKey mapKey(int32_t keyCode) {
  if (keyCode >= AKEYCODE_A && keyCode <= AKEYCODE_Z) {
    return static_cast<GhosttyKey>(GHOSTTY_KEY_A + (keyCode - AKEYCODE_A));
  }
  if (keyCode >= AKEYCODE_0 && keyCode <= AKEYCODE_9) {
    return static_cast<GhosttyKey>(GHOSTTY_KEY_DIGIT_0 + (keyCode - AKEYCODE_0));
  }
  if (keyCode >= AKEYCODE_NUMPAD_0 && keyCode <= AKEYCODE_NUMPAD_9) {
    return static_cast<GhosttyKey>(GHOSTTY_KEY_NUMPAD_0 + (keyCode - AKEYCODE_NUMPAD_0));
  }
  if (keyCode >= AKEYCODE_F1 && keyCode <= AKEYCODE_F12) {
    return static_cast<GhosttyKey>(GHOSTTY_KEY_F1 + (keyCode - AKEYCODE_F1));
  }
  switch (keyCode) {
    case AKEYCODE_GRAVE: return GHOSTTY_KEY_BACKQUOTE;
    case AKEYCODE_BACKSLASH: return GHOSTTY_KEY_BACKSLASH;
    case AKEYCODE_LEFT_BRACKET: return GHOSTTY_KEY_BRACKET_LEFT;
    case AKEYCODE_RIGHT_BRACKET: return GHOSTTY_KEY_BRACKET_RIGHT;
    case AKEYCODE_COMMA: return GHOSTTY_KEY_COMMA;
    case AKEYCODE_EQUALS: return GHOSTTY_KEY_EQUAL;
    case AKEYCODE_MINUS: return GHOSTTY_KEY_MINUS;
    case AKEYCODE_PERIOD: return GHOSTTY_KEY_PERIOD;
    case AKEYCODE_APOSTROPHE: return GHOSTTY_KEY_QUOTE;
    case AKEYCODE_SEMICOLON: return GHOSTTY_KEY_SEMICOLON;
    case AKEYCODE_SLASH: return GHOSTTY_KEY_SLASH;
    case AKEYCODE_ALT_LEFT: return GHOSTTY_KEY_ALT_LEFT;
    case AKEYCODE_ALT_RIGHT: return GHOSTTY_KEY_ALT_RIGHT;
    case AKEYCODE_DEL: return GHOSTTY_KEY_BACKSPACE;
    case AKEYCODE_CAPS_LOCK: return GHOSTTY_KEY_CAPS_LOCK;
    case AKEYCODE_CTRL_LEFT: return GHOSTTY_KEY_CONTROL_LEFT;
    case AKEYCODE_CTRL_RIGHT: return GHOSTTY_KEY_CONTROL_RIGHT;
    case AKEYCODE_ENTER: return GHOSTTY_KEY_ENTER;
    case AKEYCODE_META_LEFT: return GHOSTTY_KEY_META_LEFT;
    case AKEYCODE_META_RIGHT: return GHOSTTY_KEY_META_RIGHT;
    case AKEYCODE_SHIFT_LEFT: return GHOSTTY_KEY_SHIFT_LEFT;
    case AKEYCODE_SHIFT_RIGHT: return GHOSTTY_KEY_SHIFT_RIGHT;
    case AKEYCODE_SPACE: return GHOSTTY_KEY_SPACE;
    case AKEYCODE_TAB: return GHOSTTY_KEY_TAB;
    case AKEYCODE_FORWARD_DEL: return GHOSTTY_KEY_DELETE;
    case AKEYCODE_MOVE_END: return GHOSTTY_KEY_END;
    case AKEYCODE_MOVE_HOME: return GHOSTTY_KEY_HOME;
    case AKEYCODE_INSERT: return GHOSTTY_KEY_INSERT;
    case AKEYCODE_PAGE_DOWN: return GHOSTTY_KEY_PAGE_DOWN;
    case AKEYCODE_PAGE_UP: return GHOSTTY_KEY_PAGE_UP;
    case AKEYCODE_DPAD_DOWN: return GHOSTTY_KEY_ARROW_DOWN;
    case AKEYCODE_DPAD_LEFT: return GHOSTTY_KEY_ARROW_LEFT;
    case AKEYCODE_DPAD_RIGHT: return GHOSTTY_KEY_ARROW_RIGHT;
    case AKEYCODE_DPAD_UP: return GHOSTTY_KEY_ARROW_UP;
    case AKEYCODE_DPAD_CENTER: return GHOSTTY_KEY_ENTER;
    case AKEYCODE_SYSRQ: return GHOSTTY_KEY_PRINT_SCREEN;
    case AKEYCODE_BREAK: return GHOSTTY_KEY_PAUSE;
    case AKEYCODE_NUM_LOCK: return GHOSTTY_KEY_NUM_LOCK;
    case AKEYCODE_NUMPAD_ADD: return GHOSTTY_KEY_NUMPAD_ADD;
    case AKEYCODE_NUMPAD_COMMA: return GHOSTTY_KEY_NUMPAD_COMMA;
    case AKEYCODE_NUMPAD_DOT: return GHOSTTY_KEY_NUMPAD_DECIMAL;
    case AKEYCODE_NUMPAD_DIVIDE: return GHOSTTY_KEY_NUMPAD_DIVIDE;
    case AKEYCODE_NUMPAD_ENTER: return GHOSTTY_KEY_NUMPAD_ENTER;
    case AKEYCODE_NUMPAD_EQUALS: return GHOSTTY_KEY_NUMPAD_EQUAL;
    case AKEYCODE_NUMPAD_MULTIPLY: return GHOSTTY_KEY_NUMPAD_MULTIPLY;
    case AKEYCODE_NUMPAD_SUBTRACT: return GHOSTTY_KEY_NUMPAD_SUBTRACT;
    case AKEYCODE_ESCAPE: return GHOSTTY_KEY_ESCAPE;
    default: return GHOSTTY_KEY_UNIDENTIFIED;
  }
}

constexpr int32_t kMetaShiftOn = 0x1;
constexpr int32_t kMetaShiftRightOn = 0x80;
constexpr int32_t kMetaAltOn = 0x02;
constexpr int32_t kMetaAltRightOn = 0x20;
constexpr int32_t kMetaCtrlOn = 0x1000;
constexpr int32_t kMetaCtrlRightOn = 0x4000;
constexpr int32_t kMetaMetaOn = 0x10000;
constexpr int32_t kMetaMetaRightOn = 0x40000;
constexpr int32_t kMetaCapsLockOn = 0x100000;
constexpr int32_t kMetaNumLockOn = 0x200000;

GhosttyMods mapMods(int32_t metaState) {
  GhosttyMods mods = 0;
  if (metaState & kMetaShiftOn) mods |= GHOSTTY_MODS_SHIFT;
  if (metaState & kMetaShiftRightOn) mods |= GHOSTTY_MODS_SHIFT_SIDE;
  if (metaState & kMetaCtrlOn) mods |= GHOSTTY_MODS_CTRL;
  if (metaState & kMetaCtrlRightOn) mods |= GHOSTTY_MODS_CTRL_SIDE;
  if (metaState & kMetaAltOn) mods |= GHOSTTY_MODS_ALT;
  if (metaState & kMetaAltRightOn) mods |= GHOSTTY_MODS_ALT_SIDE;
  if (metaState & kMetaMetaOn) mods |= GHOSTTY_MODS_SUPER;
  if (metaState & kMetaMetaRightOn) mods |= GHOSTTY_MODS_SUPER_SIDE;
  if (metaState & kMetaCapsLockOn) mods |= GHOSTTY_MODS_CAPS_LOCK;
  if (metaState & kMetaNumLockOn) mods |= GHOSTTY_MODS_NUM_LOCK;
  return mods;
}

void setTerminalOption(Session* session, GhosttyTerminalOption option, const void* value,
                       const char* name) {
  if (ghostty_terminal_set(session->term, option, value) != GHOSTTY_SUCCESS) {
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "terminal_set %s failed", name);
  }
}

uint32_t appendUtf16(uint32_t cp, uint16_t* out, uint32_t used, uint32_t cap) {
  if (cp < 0x10000) {
    if (used + 1 > cap) return 0;
    out[used] = static_cast<uint16_t>(cp);
    return 1;
  }
  if (used + 2 > cap) return 0;
  cp -= 0x10000;
  out[used] = static_cast<uint16_t>(0xD800 + (cp >> 10));
  out[used + 1] = static_cast<uint16_t>(0xDC00 + (cp & 0x3FF));
  return 2;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeCreate(
    JNIEnv* env, jobject, jint cols, jint rows, jlong maxScrollbackLines, jstring xtversion) {
  auto* session = new Session();

  if (xtversion != nullptr) {
    const char* utf = env->GetStringUTFChars(xtversion, nullptr);
    if (utf != nullptr) {
      session->xtversion.assign(utf);
      env->ReleaseStringUTFChars(xtversion, utf);
    }
  }

  if (ghostty_terminal_new(&kMallocAllocator, &session->term, static_cast<uint16_t>(cols),
                           static_cast<uint16_t>(rows)) != GHOSTTY_SUCCESS ||
      ghostty_render_state_new(&kMallocAllocator, &session->renderState) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(&kMallocAllocator, &session->rowIter) !=
          GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(&kMallocAllocator, &session->cells) !=
          GHOSTTY_SUCCESS ||
      ghostty_key_encoder_new(&kMallocAllocator, &session->encoder) != GHOSTTY_SUCCESS ||
      ghostty_key_event_new(&kMallocAllocator, &session->keyEvent) != GHOSTTY_SUCCESS ||
      ghostty_mouse_encoder_new(&kMallocAllocator, &session->mouseEncoder) !=
          GHOSTTY_SUCCESS ||
      ghostty_mouse_event_new(&kMallocAllocator, &session->mouseEvent) != GHOSTTY_SUCCESS ||
      ghostty_kitty_graphics_placement_iterator_new(&kMallocAllocator, &session->kittyIterator) !=
          GHOSTTY_SUCCESS ||
      ghostty_kitty_graphics_virtual_placement_iterator_new(
          &kMallocAllocator, &session->kittyVirtualIterator) != GHOSTTY_SUCCESS) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "failed to create terminal session");
    destroySession(session);
    return 0;
  }

  session->cols = static_cast<uint16_t>(cols);
  session->rows = static_cast<uint16_t>(rows);

  const bool trackLastCell = true;
  ghostty_mouse_encoder_setopt(session->mouseEncoder,
                               GHOSTTY_MOUSE_ENCODER_OPT_TRACK_LAST_CELL, &trackLastCell);

  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_USERDATA, session, "userdata");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_WRITE_PTY,
                    reinterpret_cast<const void*>(&writePtyCallback), "write_pty");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_BELL,
                    reinterpret_cast<const void*>(&bellCallback), "bell");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED,
                    reinterpret_cast<const void*>(&titleChangedCallback), "title_changed");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE,
                    reinterpret_cast<const void*>(&clipboardWriteCallback), "clipboard_write");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES,
                    reinterpret_cast<const void*>(&deviceAttributesCallback),
                    "device_attributes");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_SIZE,
                    reinterpret_cast<const void*>(&sizeCallback), "size");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_PWD_CHANGED,
                    reinterpret_cast<const void*>(&pwdChangedCallback), "pwd_changed");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION,
                    reinterpret_cast<const void*>(&desktopNotificationCallback),
                    "desktop_notification");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_PROGRESS_REPORT,
                    reinterpret_cast<const void*>(&progressReportCallback),
                    "progress_report");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_COLOR_SCHEME,
                    reinterpret_cast<const void*>(&colorSchemeCallback), "color_scheme");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_XTVERSION,
                    reinterpret_cast<const void*>(&xtversionCallback), "xtversion");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ,
                    reinterpret_cast<const void*>(&clipboardReadCallback), "clipboard_read");

  const uint64_t kittyStorageLimit = 128 * 1024 * 1024;
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &kittyStorageLimit,
                    "kitty_image_storage_limit");

  const size_t scrollbackLines = static_cast<size_t>(maxScrollbackLines);
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &scrollbackLines,
                    "scrollback_max_lines");

  GhosttyString terminfoName{};
  terminfoName.ptr = reinterpret_cast<const uint8_t*>("xterm-256color");
  terminfoName.len = strlen("xterm-256color");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_TERMINFO_NAME, &terminfoName,
                    "terminfo_name");

  GhosttyColorRgb bg{0x00, 0x00, 0x00};
  GhosttyColorRgb fg{0xFF, 0xFF, 0xFF};
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &bg, "color_background");
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &fg, "color_foreground");

  const bool blinkDefault = true;
  setTerminalOption(session, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK, &blinkDefault,
                    "default_cursor_blink");

  return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

JNIEXPORT void JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeFree(JNIEnv*, jobject, jlong handle) {
  destroySession(fromHandle(handle));
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeWrite(
    JNIEnv* env, jobject, jlong handle, jbyteArray data, jint offset, jint length) {
  auto* session = fromHandle(handle);
  if (session == nullptr || data == nullptr) return nullptr;

  const jsize arrayLen = env->GetArrayLength(data);
  if (offset < 0 || length < 0 || offset > arrayLen - length) return nullptr;

  session->ptyOut.clear();
  if (length > 0) {
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    ghostty_terminal_vt_write(session->term,
                              reinterpret_cast<const uint8_t*>(bytes) + offset,
                              static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  }

  if (session->ptyOut.empty()) return nullptr;
  const auto outLen = static_cast<jsize>(session->ptyOut.size());
  jbyteArray out = env->NewByteArray(outLen);
  if (out == nullptr) return nullptr;
  env->SetByteArrayRegion(out, 0, outLen,
                          reinterpret_cast<const jbyte*>(session->ptyOut.data()));
  return out;
}

JNIEXPORT jint JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeTakeEventFlags(
    JNIEnv*, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return 0;
  const jint flags = session->pendingEvents;
  session->pendingEvents = 0;
  return flags;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeGetTitle(
    JNIEnv* env, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return nullptr;
  return stringData(env, session, GHOSTTY_TERMINAL_DATA_TITLE);
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeTakeClipboard(
    JNIEnv* env, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr || session->clipboardText.empty()) return nullptr;
  jbyteArray out = env->NewByteArray(static_cast<jsize>(session->clipboardText.size()));
  if (out == nullptr) return nullptr;
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(session->clipboardText.size()),
                          reinterpret_cast<const jbyte*>(session->clipboardText.data()));
  session->clipboardText.clear();
  return out;
}

JNIEXPORT void JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeResize(
    JNIEnv*, jobject, jlong handle, jint cols, jint rows, jint cellWidthPx, jint cellHeightPx) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return;
  session->cols = static_cast<uint16_t>(cols);
  session->rows = static_cast<uint16_t>(rows);
  session->cellWidthPx = static_cast<uint32_t>(cellWidthPx);
  session->cellHeightPx = static_cast<uint32_t>(cellHeightPx);
  ghostty_terminal_resize(session->term, static_cast<uint16_t>(cols),
                          static_cast<uint16_t>(rows), static_cast<uint32_t>(cellWidthPx),
                          static_cast<uint32_t>(cellHeightPx));
  markRenderDirtyFull(session);
}

static bool colorFromArgb(jlong value, GhosttyColorRgb* out) {
  if (value < 0) return false;
  out->r = static_cast<uint8_t>((value >> 16) & 0xFF);
  out->g = static_cast<uint8_t>((value >> 8) & 0xFF);
  out->b = static_cast<uint8_t>(value & 0xFF);
  return true;
}

JNIEXPORT void JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeSetTheme(
    JNIEnv* env, jobject, jlong handle, jlong foreground, jlong background,
    jlong cursor, jlongArray palette) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return;

  GhosttyColorRgb rgb{};
  ghostty_terminal_set(session->term, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND,
                       colorFromArgb(foreground, &rgb) ? &rgb : nullptr);
  ghostty_terminal_set(session->term, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND,
                       colorFromArgb(background, &rgb) ? &rgb : nullptr);
  ghostty_terminal_set(session->term, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR,
                       colorFromArgb(cursor, &rgb) ? &rgb : nullptr);

  if (palette == nullptr) {
    ghostty_terminal_set(session->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, nullptr);
  } else {
    GhosttyColorRgb table[256];
    ghostty_color_palette_default(table);
    jsize count = env->GetArrayLength(palette);
    if (count > 256) count = 256;
    jlong* entries = env->GetLongArrayElements(palette, nullptr);
    for (jsize i = 0; i < count; i++) {
      GhosttyColorRgb parsed{};
      if (colorFromArgb(entries[i], &parsed)) table[i] = parsed;
    }
    env->ReleaseLongArrayElements(palette, entries, JNI_ABORT);
    ghostty_terminal_set(session->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, table);
  }

  markRenderDirtyFull(session);
}

JNIEXPORT jint JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeParseColor(
    JNIEnv* env, jobject, jstring color) {
  GhosttyColorRgb rgb{};
  return parseColor(env, color, &rgb) ? argb(rgb) : 0;
}

JNIEXPORT void JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeScroll(
    JNIEnv*, jobject, jlong handle, jint deltaRows) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return;
  GhosttyTerminalScrollViewport behavior{};
  behavior.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
  behavior.value.delta = deltaRows;
  ghostty_terminal_scroll_viewport(session->term, behavior);
}

JNIEXPORT void JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeScrollToBottom(
    JNIEnv*, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return;
  GhosttyTerminalScrollViewport behavior{};
  behavior.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
  ghostty_terminal_scroll_viewport(session->term, behavior);
}

JNIEXPORT jint JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeViewportState(
    JNIEnv*, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return 0;
  jint state = 0;
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  if (ghostty_terminal_get(session->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen) ==
          GHOSTTY_SUCCESS &&
      screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE) {
    state |= 1;
  }
  bool mouseTracking = false;
  if (ghostty_terminal_get(session->term, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING,
                           &mouseTracking) == GHOSTTY_SUCCESS &&
      mouseTracking) {
    state |= 2;
  }
  GhosttyTerminalModeConfig sgrMouse{};
  sgrMouse.mode = GHOSTTY_MODE_SGR_MOUSE;
  if (ghostty_terminal_get(session->term, GHOSTTY_TERMINAL_DATA_MODE, &sgrMouse) ==
          GHOSTTY_SUCCESS &&
      sgrMouse.value) {
    state |= 4;
  }
  return state;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeScrollbar(
    JNIEnv* env, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return nullptr;
  GhosttyTerminalScrollbar scrollbar{};
  if (ghostty_terminal_get(session->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scrollbar) !=
      GHOSTTY_SUCCESS) {
    return nullptr;
  }
  jlongArray out = env->NewLongArray(3);
  if (out == nullptr) return nullptr;
  const jlong values[3] = {static_cast<jlong>(scrollbar.total),
                           static_cast<jlong>(scrollbar.offset),
                           static_cast<jlong>(scrollbar.len)};
  env->SetLongArrayRegion(out, 0, 3, values);
  return out;
}

JNIEXPORT jint JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeSnapshot(
    JNIEnv* env, jobject, jlong handle, jobject buffer) {
  auto* session = fromHandle(handle);
  if (session == nullptr || buffer == nullptr) return -1;
  auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
  const auto cap = static_cast<size_t>(env->GetDirectBufferCapacity(buffer));
  if (base == nullptr || cap < kHeaderBytes) return -1;

  if (modeSet(session, GHOSTTY_MODE_SYNC_OUTPUT)) {
    const auto now = std::chrono::steady_clock::now();
    if (!session->syncOutputActive) {
      session->syncOutputActive = true;
      session->syncOutputSince = now;
      return -2;
    }
    if (now - session->syncOutputSince < kSyncOutputTimeout) return -2;
  } else {
    session->syncOutputActive = false;
  }

  if (ghostty_render_state_update(session->renderState, session->term) != GHOSTTY_SUCCESS) {
    return -1;
  }

  GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
  ghostty_render_state_get(session->renderState, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);

  uint16_t cols = 0;
  uint16_t rows = 0;
  ghostty_render_state_get(session->renderState, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(session->renderState, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);

  GhosttyRenderStateColors colors{};
  colors.size = sizeof(colors);
  ghostty_render_state_get(session->renderState, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);

  GhosttyRenderStateCursor cursor{};
  cursor.size = sizeof(cursor);
  ghostty_render_state_get(session->renderState, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);

  bool passwordInput = false;
  ghostty_render_state_get(session->renderState,
                           GHOSTTY_RENDER_STATE_DATA_CURSOR_PASSWORD_INPUT, &passwordInput);

  bool cursorColorSet = false;
  ghostty_render_state_get(session->renderState,
                           GHOSTTY_RENDER_STATE_DATA_COLOR_CURSOR_HAS_VALUE, &cursorColorSet);
  int32_t cursorColor = 0;
  if (cursorColorSet) {
    GhosttyColorRgb cursorRgb{};
    ghostty_render_state_get(session->renderState, GHOSTTY_RENDER_STATE_DATA_COLOR_CURSOR,
                             &cursorRgb);
    cursorColor = argb(cursorRgb);
  }

  auto* header = reinterpret_cast<int32_t*>(base);
  header[0] = 2;
  header[1] = static_cast<int32_t>(dirty);
  header[2] = cols;
  header[3] = rows;
  header[4] = argb(colors.background);
  header[5] = argb(colors.foreground);
  header[6] = cursor.viewport_has_value ? cursor.viewport_x : -1;
  header[7] = cursor.viewport_has_value ? cursor.viewport_y : -1;
  header[8] = static_cast<int32_t>(cursor.visual_style);
  header[9] = cursor.visible ? 1 : 0;
  header[10] = 0;
  header[11] = cursor.blinking ? 1 : 0;
  header[12] = cursorColor;
  header[13] = 0;
  header[14] = passwordInput ? 1 : 0;

  if (dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE) return 0;

  if (ghostty_render_state_get(session->renderState, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                               &session->rowIter) != GHOSTTY_SUCCESS) {
    return -1;
  }

  GhosttyColorRgb palette[256];
  const bool paletteValid =
      ghostty_render_state_get(session->renderState, GHOSTTY_RENDER_STATE_DATA_COLOR_PALETTE,
                               palette) == GHOSTTY_SUCCESS;

  size_t offset = kHeaderBytes;
  int32_t rowCount = 0;
  int32_t rowIndex = -1;
  bool truncated = false;
  std::vector<uint32_t> bigGrapheme;

  while (ghostty_render_state_row_iterator_next(session->rowIter)) {
    rowIndex++;

    bool rowDirty = false;
    ghostty_render_state_row_get(session->rowIter, GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY,
                                 &rowDirty);
    if (dirty != GHOSTTY_RENDER_STATE_DIRTY_FULL && !rowDirty) continue;

    const size_t cellsOffset = offset + kRowHeaderBytes;
    const size_t textOffset = cellsOffset + static_cast<size_t>(cols) * kCellRecordBytes;
    if (textOffset > cap) {
      truncated = true;
      break;
    }

    auto* rowHeader = reinterpret_cast<int32_t*>(base + offset);
    rowHeader[0] = rowIndex;

    GhosttyRenderStateRowSelection selection{};
    selection.size = sizeof(selection);
    const GhosttyResult selResult = ghostty_render_state_row_get(
        session->rowIter, GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION, &selection);
    rowHeader[1] = selResult == GHOSTTY_SUCCESS ? selection.start_x : -1;
    rowHeader[2] = selResult == GHOSTTY_SUCCESS ? selection.end_x : -1;

    if (ghostty_render_state_row_get(session->rowIter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                                     &session->cells) != GHOSTTY_SUCCESS) {
      truncated = true;
      break;
    }

    auto* textBlob = reinterpret_cast<uint16_t*>(base + textOffset);
    const uint32_t textCapUnits = static_cast<uint32_t>((cap - textOffset) / 2);
    uint32_t textUnits = 0;

    int32_t col = 0;
    while (col < cols && ghostty_render_state_row_cells_next(session->cells)) {
      uint8_t* record = base + cellsOffset + static_cast<size_t>(col) * kCellRecordBytes;
      uint16_t flags = 0;
      int32_t fg = 0;
      int32_t bg = 0;

      GhosttyCell raw = 0;
      ghostty_render_state_row_cells_get(session->cells,
                                         GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw);
      GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
      ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
      if (wide == GHOSTTY_CELL_WIDE_WIDE) flags |= kFlagWide;
      if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) {
        flags |= kFlagSpacer;
      }

      bool hasStyling = false;
      ghostty_render_state_row_cells_get(session->cells,
                                         GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING,
                                         &hasStyling);
      uint16_t underlineStyle = 0;
      int32_t brightIndex = -1;
      if (hasStyling) {
        GhosttyStyle style{};
        style.size = sizeof(style);
        ghostty_render_state_row_cells_get(session->cells,
                                           GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
        if (style.bold || style.blink) flags |= kFlagBold;
        if (style.italic) flags |= kFlagItalic;
        if (style.faint) flags |= kFlagFaint;
        if (style.underline != 0) {
          flags |= kFlagUnderline;
          underlineStyle = static_cast<uint16_t>(style.underline & 0x7);
        }
        if (style.strikethrough) flags |= kFlagStrikethrough;
        if (style.inverse) flags |= kFlagInverse;
        if (style.invisible) flags |= kFlagInvisible;
        if (style.bold && paletteValid &&
            style.fg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE &&
            style.fg_color.value.palette < 8) {
          brightIndex = style.fg_color.value.palette + 8;
        }
      }

      GhosttyColorRgb cellColor{};
      if (brightIndex >= 0) {
        fg = argb(palette[brightIndex]);
      } else if (ghostty_render_state_row_cells_get(session->cells,
                                                    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
                                                    &cellColor) == GHOSTTY_SUCCESS) {
        fg = argb(cellColor);
      } else {
        flags |= kFlagFgDefault;
      }
      if (ghostty_render_state_row_cells_get(session->cells,
                                             GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
                                             &cellColor) == GHOSTTY_SUCCESS) {
        bg = argb(cellColor);
      } else {
        flags |= kFlagBgNone;
      }

      const uint32_t cellTextStart = textUnits;
      uint32_t cellUnits = 0;
      uint32_t graphemeLen = 0;
      if ((flags & kFlagSpacer) == 0) {
        ghostty_render_state_row_cells_get(session->cells,
                                           GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN,
                                           &graphemeLen);
      }
      if (graphemeLen > 0) {
        uint32_t stackBuf[16];
        uint32_t* codepoints = stackBuf;
        if (graphemeLen > 16) {
          bigGrapheme.resize(graphemeLen);
          codepoints = bigGrapheme.data();
        }
        ghostty_render_state_row_cells_get(session->cells,
                                           GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
                                           codepoints);
        if (codepoints[0] == kKittyPlaceholder) graphemeLen = 0;
        for (uint32_t i = 0; i < graphemeLen; i++) {
          const uint32_t needed = codepoints[i] >= 0x10000 ? 2 : 1;
          if (cellUnits + needed > kMaxCellTextUnits) break;
          const uint32_t written =
              appendUtf16(codepoints[i], textBlob, textUnits + cellUnits, textCapUnits);
          if (written == 0) break;
          cellUnits += written;
        }
      }
      textUnits += cellUnits;

      *reinterpret_cast<int32_t*>(record) = fg;
      *reinterpret_cast<int32_t*>(record + 4) = bg;
      *reinterpret_cast<uint16_t*>(record + 8) = static_cast<uint16_t>(cellTextStart);
      *reinterpret_cast<uint16_t*>(record + 10) = static_cast<uint16_t>(cellUnits);
      *reinterpret_cast<uint16_t*>(record + 12) = flags;
      *reinterpret_cast<uint16_t*>(record + 14) = underlineStyle;
      col++;
    }

    for (; col < cols; col++) {
      uint8_t* record = base + cellsOffset + static_cast<size_t>(col) * kCellRecordBytes;
      memset(record, 0, kCellRecordBytes);
      *reinterpret_cast<uint16_t*>(record + 12) = kFlagFgDefault | kFlagBgNone;
    }

    rowHeader[3] = static_cast<int32_t>(textUnits);
    offset = textOffset + static_cast<size_t>(textUnits) * 2;
    if (offset % 4 != 0) {
      if (offset + 2 > cap) {
        truncated = true;
        break;
      }
      *reinterpret_cast<uint16_t*>(base + offset) = 0;
      offset += 2;
    }
    rowCount++;
  }

  if (!truncated) {
    ghostty_render_state_clean(session->renderState);
  }

  header[10] = rowCount;
  header[13] = truncated ? 1 : 0;
  return rowCount;
}

JNIEXPORT jboolean JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeSelectWord(
    JNIEnv*, jobject, jlong handle, jint col, jint row) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return JNI_FALSE;
  GhosttyGridRef ref{};
  ref.size = sizeof(ref);
  if (!viewportGridRef(session, col, row, &ref)) return JNI_FALSE;
  GhosttyTerminalSelectWordOptions options{};
  options.size = sizeof(options);
  options.ref = ref;
  GhosttySelection selection{};
  selection.size = sizeof(selection);
  if (ghostty_terminal_select_word(session->term, &options, &selection) != GHOSTTY_SUCCESS) {
    return JNI_FALSE;
  }
  return installSelection(session, &selection) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeSelectAll(JNIEnv*, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return JNI_FALSE;
  GhosttySelection selection{};
  selection.size = sizeof(selection);
  if (ghostty_terminal_select_all(session->term, &selection) != GHOSTTY_SUCCESS) {
    return JNI_FALSE;
  }
  return installSelection(session, &selection) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeSetSelection(
    JNIEnv*, jobject, jlong handle, jint anchorCol, jint anchorRow, jint col, jint row) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return JNI_FALSE;
  GhosttyGridRef start{};
  start.size = sizeof(start);
  GhosttyGridRef end{};
  end.size = sizeof(end);
  if (!viewportGridRef(session, anchorCol, anchorRow, &start) ||
      !viewportGridRef(session, col, row, &end)) {
    return JNI_FALSE;
  }
  GhosttySelection selection{};
  selection.size = sizeof(selection);
  selection.start = start;
  selection.end = end;
  selection.rectangle = false;
  return installSelection(session, &selection) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeClearSelection(JNIEnv*, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return;
  ghostty_terminal_set(session->term, GHOSTTY_TERMINAL_OPT_SELECTION, nullptr);
  markRenderDirtyFull(session);
}

jbyteArray formatSelection(JNIEnv* env, Session* session, const GhosttySelection* selection) {
  GhosttyTerminalSelectionFormatOptions options{};
  options.size = sizeof(options);
  options.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  options.unwrap = true;
  options.trim = true;
  options.selection = selection;

  size_t needed = 0;
  GhosttyResult result =
      ghostty_terminal_selection_format_buf(session->term, options, nullptr, 0, &needed);
  if (result != GHOSTTY_OUT_OF_SPACE && result != GHOSTTY_SUCCESS) return nullptr;

  std::vector<uint8_t> buf(needed);
  size_t written = 0;
  if (needed > 0) {
    result = ghostty_terminal_selection_format_buf(session->term, options, buf.data(),
                                                   buf.size(), &written);
    if (result != GHOSTTY_SUCCESS) return nullptr;
  }
  jbyteArray out = env->NewByteArray(static_cast<jsize>(written));
  if (out == nullptr) return nullptr;
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(written),
                          reinterpret_cast<const jbyte*>(buf.data()));
  return out;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeSelectionText(
    JNIEnv* env, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return nullptr;
  return formatSelection(env, session, nullptr);
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeViewportText(
    JNIEnv* env, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr || session->cols == 0 || session->rows == 0) return nullptr;
  GhosttyGridRef start{};
  start.size = sizeof(start);
  GhosttyGridRef end{};
  end.size = sizeof(end);
  if (!viewportGridRef(session, 0, 0, &start) ||
      !viewportGridRef(session, session->cols - 1, session->rows - 1, &end)) {
    return nullptr;
  }
  GhosttySelection selection{};
  selection.size = sizeof(selection);
  selection.start = start;
  selection.end = end;
  selection.rectangle = false;
  return formatSelection(env, session, &selection);
}

JNIEXPORT jboolean JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeIsPasteSafe(
    JNIEnv* env, jobject, jbyteArray data) {
  if (data == nullptr) return JNI_TRUE;
  const jsize len = env->GetArrayLength(data);
  if (len == 0) return JNI_TRUE;
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  const bool safe = ghostty_paste_is_safe(reinterpret_cast<const char*>(bytes),
                                          static_cast<size_t>(len));
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  return safe ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeEncodePaste(
    JNIEnv* env, jobject, jlong handle, jbyteArray data) {
  auto* session = fromHandle(handle);
  if (session == nullptr || data == nullptr) return nullptr;
  const jsize len = env->GetArrayLength(data);
  std::vector<char> input(static_cast<size_t>(len));
  if (len > 0) {
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(input.data()));
  }

  GhosttyTerminalModeConfig bracketed{};
  bracketed.mode = GHOSTTY_MODE_BRACKETED_PASTE;
  if (ghostty_terminal_get(session->term, GHOSTTY_TERMINAL_DATA_MODE, &bracketed) !=
      GHOSTTY_SUCCESS) {
    bracketed.value = false;
  }

  std::vector<char> out(input.size() + 16);
  size_t written = 0;
  GhosttyResult result = ghostty_paste_encode(input.data(), input.size(), bracketed.value,
                                              out.data(), out.size(), &written);
  if (result == GHOSTTY_OUT_OF_SPACE) {
    out.resize(written);
    result = ghostty_paste_encode(input.data(), input.size(), bracketed.value, out.data(),
                                  out.size(), &written);
  }
  if (result != GHOSTTY_SUCCESS) return nullptr;
  jbyteArray outArray = env->NewByteArray(static_cast<jsize>(written));
  if (outArray == nullptr) return nullptr;
  env->SetByteArrayRegion(outArray, 0, static_cast<jsize>(written),
                          reinterpret_cast<const jbyte*>(out.data()));
  return outArray;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeEncodeKey(
    JNIEnv* env, jobject, jlong handle, jint keyCode, jint action, jint metaState,
    jint unshiftedCodepoint, jboolean composing, jbyteArray utf8) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return nullptr;

  ghostty_key_encoder_setopt_from_terminal(session->encoder, session->term);

  GhosttyKeyAction keyAction = GHOSTTY_KEY_ACTION_PRESS;
  if (action == 0) keyAction = GHOSTTY_KEY_ACTION_RELEASE;
  if (action == 2) keyAction = GHOSTTY_KEY_ACTION_REPEAT;

  ghostty_key_event_set_action(session->keyEvent, keyAction);
  ghostty_key_event_set_key(session->keyEvent, mapKey(keyCode));
  ghostty_key_event_set_mods(session->keyEvent, mapMods(metaState));
  ghostty_key_event_set_consumed_mods(session->keyEvent, 0);
  ghostty_key_event_set_composing(session->keyEvent, composing != JNI_FALSE);
  ghostty_key_event_set_unshifted_codepoint(session->keyEvent,
                                            static_cast<uint32_t>(unshiftedCodepoint));

  jbyte* utf8Bytes = nullptr;
  jsize utf8Len = 0;
  if (utf8 != nullptr) {
    utf8Len = env->GetArrayLength(utf8);
    if (utf8Len > 0) utf8Bytes = env->GetByteArrayElements(utf8, nullptr);
  }
  ghostty_key_event_set_utf8(session->keyEvent,
                             reinterpret_cast<const char*>(utf8Bytes),
                             static_cast<size_t>(utf8Bytes != nullptr ? utf8Len : 0));

  char stackBuf[128];
  size_t written = 0;
  GhosttyResult result = ghostty_key_encoder_encode(session->encoder, session->keyEvent,
                                                    stackBuf, sizeof(stackBuf), &written);
  std::vector<char> heapBuf;
  const char* out = stackBuf;
  if (result == GHOSTTY_OUT_OF_SPACE) {
    heapBuf.resize(written);
    result = ghostty_key_encoder_encode(session->encoder, session->keyEvent, heapBuf.data(),
                                        heapBuf.size(), &written);
    out = heapBuf.data();
  }

  ghostty_key_event_set_utf8(session->keyEvent, nullptr, 0);
  if (utf8Bytes != nullptr) env->ReleaseByteArrayElements(utf8, utf8Bytes, JNI_ABORT);

  if (result != GHOSTTY_SUCCESS || written == 0) return nullptr;
  jbyteArray outArray = env->NewByteArray(static_cast<jsize>(written));
  if (outArray == nullptr) return nullptr;
  env->SetByteArrayRegion(outArray, 0, static_cast<jsize>(written),
                          reinterpret_cast<const jbyte*>(out));
  return outArray;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeEncodeMouse(
    JNIEnv* env, jobject, jlong handle, jint action, jint button, jint col, jint row,
    jint metaState) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return nullptr;

  ghostty_mouse_encoder_setopt_from_terminal(session->mouseEncoder, session->term);

  GhosttyMouseEncoderSize size{};
  size.size = sizeof(size);
  size.screen_width = session->cols;
  size.screen_height = session->rows;
  size.cell_width = 1;
  size.cell_height = 1;
  ghostty_mouse_encoder_setopt(session->mouseEncoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);

  const auto mouseAction = static_cast<GhosttyMouseAction>(action);
  const auto mouseButton = static_cast<GhosttyMouseButton>(button);
  if (mouseButton >= GHOSTTY_MOUSE_BUTTON_LEFT && mouseButton <= GHOSTTY_MOUSE_BUTTON_MIDDLE) {
    if (mouseAction == GHOSTTY_MOUSE_ACTION_PRESS) session->mouseButtonDown = true;
    if (mouseAction == GHOSTTY_MOUSE_ACTION_RELEASE) session->mouseButtonDown = false;
  }
  ghostty_mouse_encoder_setopt(session->mouseEncoder,
                               GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED,
                               &session->mouseButtonDown);

  ghostty_mouse_event_set_action(session->mouseEvent, mouseAction);
  if (mouseButton == GHOSTTY_MOUSE_BUTTON_UNKNOWN) {
    ghostty_mouse_event_clear_button(session->mouseEvent);
  } else {
    ghostty_mouse_event_set_button(session->mouseEvent, mouseButton);
  }
  ghostty_mouse_event_set_mods(session->mouseEvent, mapMods(metaState));
  GhosttyMousePosition position{static_cast<float>(col), static_cast<float>(row)};
  ghostty_mouse_event_set_position(session->mouseEvent, position);

  char buf[64];
  size_t written = 0;
  if (ghostty_mouse_encoder_encode(session->mouseEncoder, session->mouseEvent, buf,
                                   sizeof(buf), &written) != GHOSTTY_SUCCESS ||
      written == 0) {
    return nullptr;
  }
  jbyteArray outArray = env->NewByteArray(static_cast<jsize>(written));
  if (outArray == nullptr) return nullptr;
  env->SetByteArrayRegion(outArray, 0, static_cast<jsize>(written),
                          reinterpret_cast<const jbyte*>(buf));
  return outArray;
}

JNIEXPORT jlongArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeKittyPlacements(
    JNIEnv* env, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr || session->kittyIterator == nullptr) return nullptr;
  GhosttyKittyGraphics graphics = nullptr;
  if (ghostty_terminal_get(session->term, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics) !=
      GHOSTTY_SUCCESS) {
    return nullptr;
  }
  uint64_t generation = 0;
  if (ghostty_kitty_graphics_get(graphics, GHOSTTY_KITTY_GRAPHICS_DATA_GENERATION,
                                 &generation) != GHOSTTY_SUCCESS ||
      generation == 0) {
    return nullptr;
  }
  if (ghostty_kitty_graphics_get(graphics, GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR,
                                 &session->kittyIterator) != GHOSTTY_SUCCESS) {
    return nullptr;
  }

  constexpr size_t kPlacementLongs = 13;
  std::vector<std::array<int64_t, kPlacementLongs>> records;
  while (ghostty_kitty_graphics_placement_next(session->kittyIterator)) {
    bool isVirtual = false;
    ghostty_kitty_graphics_placement_get(session->kittyIterator,
                                         GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IS_VIRTUAL,
                                         &isVirtual);
    if (isVirtual) continue;
    uint32_t imageId = 0;
    if (ghostty_kitty_graphics_placement_get(session->kittyIterator,
                                             GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID,
                                             &imageId) != GHOSTTY_SUCCESS) {
      continue;
    }
    GhosttyKittyGraphicsImage image = ghostty_kitty_graphics_image(graphics, imageId);
    if (image == nullptr) continue;
    auto info = GHOSTTY_INIT_SIZED(GhosttyKittyGraphicsPlacementRenderInfo);
    if (ghostty_kitty_graphics_placement_render_info(session->kittyIterator, image,
                                                     session->term, &info) != GHOSTTY_SUCCESS ||
        !info.viewport_visible || info.pixel_width == 0 || info.pixel_height == 0 ||
        info.source_width == 0 || info.source_height == 0) {
      continue;
    }
    uint64_t imageGeneration = 0;
    ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_GENERATION,
                                     &imageGeneration);
    uint32_t xOffset = 0;
    uint32_t yOffset = 0;
    int32_t z = 0;
    ghostty_kitty_graphics_placement_get(session->kittyIterator,
                                         GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_X_OFFSET,
                                         &xOffset);
    ghostty_kitty_graphics_placement_get(session->kittyIterator,
                                         GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Y_OFFSET,
                                         &yOffset);
    ghostty_kitty_graphics_placement_get(session->kittyIterator,
                                         GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z, &z);
    const std::array<int64_t, kPlacementLongs> record = {
        imageId, static_cast<int64_t>(imageGeneration), info.viewport_col, info.viewport_row,
        xOffset, yOffset, info.pixel_width, info.pixel_height, info.source_x, info.source_y,
        info.source_width, info.source_height, z};
    records.push_back(record);
  }

  if (ghostty_kitty_graphics_virtual_placement_iterator_begin(
          session->term, session->kittyVirtualIterator) == GHOSTTY_SUCCESS) {
    while (ghostty_kitty_graphics_virtual_placement_iterator_next(session->kittyVirtualIterator)) {
      auto virtualInfo = GHOSTTY_INIT_SIZED(GhosttyKittyGraphicsVirtualRenderInfo);
      if (ghostty_kitty_graphics_virtual_placement_render_info(
              session->kittyVirtualIterator, session->term, &virtualInfo) != GHOSTTY_SUCCESS) {
        continue;
      }
      GhosttyKittyGraphicsImage image =
          ghostty_kitty_graphics_image(graphics, virtualInfo.image_id);
      if (image == nullptr) continue;
      uint64_t imageGeneration = 0;
      ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_GENERATION,
                                       &imageGeneration);
      const std::array<int64_t, kPlacementLongs> record = {
          virtualInfo.image_id, static_cast<int64_t>(imageGeneration),
          virtualInfo.viewport_col, virtualInfo.viewport_row, virtualInfo.offset_x,
          virtualInfo.offset_y, virtualInfo.dest_width, virtualInfo.dest_height,
          virtualInfo.source_x, virtualInfo.source_y, virtualInfo.source_width,
          virtualInfo.source_height, 0};
      records.push_back(record);
    }
  }

  if (records.empty()) return nullptr;

  std::stable_sort(records.begin(), records.end(),
                   [](const auto& a, const auto& b) { return a[12] < b[12]; });

  const auto outLen = static_cast<jsize>(records.size() * kPlacementLongs);
  jlongArray out = env->NewLongArray(outLen);
  if (out == nullptr) return nullptr;
  for (size_t i = 0; i < records.size(); i++) {
    static_assert(sizeof(jlong) == sizeof(int64_t), "jlong is not 64-bit");
    env->SetLongArrayRegion(out, static_cast<jsize>(i * kPlacementLongs), kPlacementLongs,
                            reinterpret_cast<const jlong*>(records[i].data()));
  }
  return out;
}

JNIEXPORT jintArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeKittyImage(
    JNIEnv* env, jobject, jlong handle, jint imageId) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return nullptr;
  GhosttyKittyGraphics graphics = nullptr;
  if (ghostty_terminal_get(session->term, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics) !=
      GHOSTTY_SUCCESS) {
    return nullptr;
  }
  GhosttyKittyGraphicsImage image =
      ghostty_kitty_graphics_image(graphics, static_cast<uint32_t>(imageId));
  if (image == nullptr) return nullptr;

  uint32_t width = 0;
  uint32_t height = 0;
  GhosttyKittyImageFormat format = GHOSTTY_KITTY_IMAGE_FORMAT_RGBA;
  const uint8_t* data = nullptr;
  size_t dataLen = 0;
  if (ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_WIDTH, &width) !=
          GHOSTTY_SUCCESS ||
      ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_HEIGHT, &height) !=
          GHOSTTY_SUCCESS ||
      ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_FORMAT, &format) !=
          GHOSTTY_SUCCESS ||
      ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR, &data) !=
          GHOSTTY_SUCCESS ||
      ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN, &dataLen) !=
          GHOSTTY_SUCCESS ||
      data == nullptr) {
    return nullptr;
  }

  size_t bytesPerPixel;
  switch (format) {
    case GHOSTTY_KITTY_IMAGE_FORMAT_RGB: bytesPerPixel = 3; break;
    case GHOSTTY_KITTY_IMAGE_FORMAT_RGBA: bytesPerPixel = 4; break;
    case GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA: bytesPerPixel = 2; break;
    case GHOSTTY_KITTY_IMAGE_FORMAT_GRAY: bytesPerPixel = 1; break;
    default: return nullptr;
  }
  const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
  if (pixelCount == 0 || pixelCount > static_cast<size_t>(kMaxImagePixels) ||
      dataLen < pixelCount * bytesPerPixel) {
    return nullptr;
  }

  jintArray out = env->NewIntArray(static_cast<jsize>(2 + pixelCount));
  if (out == nullptr) return nullptr;
  auto* dest = static_cast<jint*>(env->GetPrimitiveArrayCritical(out, nullptr));
  if (dest == nullptr) return nullptr;
  dest[0] = static_cast<jint>(width);
  dest[1] = static_cast<jint>(height);
  for (size_t i = 0; i < pixelCount; i++) {
    const uint8_t* px = data + i * bytesPerPixel;
    uint32_t a;
    uint32_t r;
    uint32_t g;
    uint32_t b;
    switch (format) {
      case GHOSTTY_KITTY_IMAGE_FORMAT_RGB:
        r = px[0]; g = px[1]; b = px[2]; a = 0xFF;
        break;
      case GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA:
        r = g = b = px[0]; a = px[1];
        break;
      case GHOSTTY_KITTY_IMAGE_FORMAT_GRAY:
        r = g = b = px[0]; a = 0xFF;
        break;
      default:
        r = px[0]; g = px[1]; b = px[2]; a = px[3];
        break;
    }
    dest[2 + i] = static_cast<jint>((a << 24) | (r << 16) | (g << 8) | b);
  }
  env->ReleasePrimitiveArrayCritical(out, dest, 0);
  return out;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeGetPwd(
    JNIEnv* env, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return nullptr;
  return stringData(env, session, GHOSTTY_TERMINAL_DATA_PWD);
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeTakeNotification(
    JNIEnv* env, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr || session->notifications.empty()) return nullptr;
  const auto [title, body] = std::move(session->notifications.front());
  session->notifications.pop_front();
  std::string packed;
  packed.reserve(4 + title.size() + body.size());
  const auto titleLen = static_cast<uint32_t>(title.size());
  packed.push_back(static_cast<char>(titleLen & 0xFF));
  packed.push_back(static_cast<char>((titleLen >> 8) & 0xFF));
  packed.push_back(static_cast<char>((titleLen >> 16) & 0xFF));
  packed.push_back(static_cast<char>((titleLen >> 24) & 0xFF));
  packed.append(title);
  packed.append(body);
  return bytesToArray(env, packed.data(), packed.size());
}

JNIEXPORT jintArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeGetProgress(
    JNIEnv* env, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return nullptr;
  jintArray out = env->NewIntArray(2);
  if (out == nullptr) return nullptr;
  const jint values[2] = {session->progressState, session->progressPercent};
  env->SetIntArrayRegion(out, 0, 2, values);
  return out;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeSetColorScheme(
    JNIEnv* env, jobject, jlong handle, jboolean dark) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return nullptr;
  const bool newDark = dark != JNI_FALSE;
  const bool changed = session->colorSchemeDark != newDark;
  session->colorSchemeDark = newDark;
  if (!changed || !modeSet(session, GHOSTTY_MODE_COLOR_SCHEME_REPORT)) return nullptr;
  char buf[16];
  size_t written = 0;
  if (ghostty_color_scheme_report_encode(
          newDark ? GHOSTTY_COLOR_SCHEME_DARK : GHOSTTY_COLOR_SCHEME_LIGHT, buf,
          sizeof(buf), &written) != GHOSTTY_SUCCESS ||
      written == 0) {
    return nullptr;
  }
  return bytesToArray(env, buf, written);
}

JNIEXPORT jint JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeTakeClipboardRead(
    JNIEnv*, jobject, jlong handle) {
  auto* session = fromHandle(handle);
  if (session == nullptr) return -1;
  const jint location = session->clipboardReadLocation;
  session->clipboardReadLocation = -1;
  return location;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeHyperlinkAt(
    JNIEnv* env, jobject, jlong handle, jint col, jint row) {
  auto* session = fromHandle(handle);
  if (session == nullptr || col < 0 || row < 0) return nullptr;
  if (ghostty_render_state_update(session->renderState, session->term) != GHOSTTY_SUCCESS) {
    return nullptr;
  }
  GhosttyString uri{};
  if (ghostty_render_state_link_uri_at(session->renderState, static_cast<uint16_t>(col),
                                       static_cast<uint32_t>(row), &uri) != GHOSTTY_SUCCESS ||
      uri.len == 0) {
    return nullptr;
  }
  return bytesToArray(env, reinterpret_cast<const char*>(uri.ptr), uri.len);
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sagernet_libghostty_GhosttyVt_nativeEncodeFocus(
    JNIEnv* env, jobject, jlong handle, jboolean gained) {
  auto* session = fromHandle(handle);
  if (session == nullptr || !modeSet(session, GHOSTTY_MODE_FOCUS_EVENT)) return nullptr;
  char buf[16];
  size_t written = 0;
  if (ghostty_focus_encode(gained != JNI_FALSE ? GHOSTTY_FOCUS_GAINED : GHOSTTY_FOCUS_LOST,
                           buf, sizeof(buf), &written) != GHOSTTY_SUCCESS ||
      written == 0) {
    return nullptr;
  }
  return bytesToArray(env, buf, written);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  gJavaVm = vm;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass factoryClass = env->FindClass("android/graphics/BitmapFactory");
  jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
  if (factoryClass == nullptr || bitmapClass == nullptr) return JNI_ERR;
  gBitmapFactoryClass = static_cast<jclass>(env->NewGlobalRef(factoryClass));
  gBitmapClass = static_cast<jclass>(env->NewGlobalRef(bitmapClass));
  gDecodeByteArray = env->GetStaticMethodID(gBitmapFactoryClass, "decodeByteArray",
                                            "([BII)Landroid/graphics/Bitmap;");
  gBitmapGetWidth = env->GetMethodID(gBitmapClass, "getWidth", "()I");
  gBitmapGetHeight = env->GetMethodID(gBitmapClass, "getHeight", "()I");
  gBitmapGetPixels = env->GetMethodID(gBitmapClass, "getPixels", "([IIIIIII)V");
  gBitmapRecycle = env->GetMethodID(gBitmapClass, "recycle", "()V");
  if (gDecodeByteArray == nullptr || gBitmapGetWidth == nullptr || gBitmapGetHeight == nullptr ||
      gBitmapGetPixels == nullptr || gBitmapRecycle == nullptr) {
    return JNI_ERR;
  }
  ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG,
                  reinterpret_cast<const void*>(&decodePngCallback));
  return JNI_VERSION_1_6;
}

}  // extern "C"
