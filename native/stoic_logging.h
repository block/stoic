#pragma once

#include <android/log.h>
#include <sstream>
#include <cstdlib>

// Minimal logging support - replaces android-base/logging.h
namespace stoic {
namespace logging {

class LogMessage {
 public:
  LogMessage(const char* file, int line, int priority)
      : file_(file), line_(line), priority_(priority) {}

  ~LogMessage() {
    __android_log_print(priority_, "stoic", "%s:%d: %s",
                       file_, line_, stream_.str().c_str());
    if (priority_ == ANDROID_LOG_FATAL) {
      abort();
    }
  }

  std::ostream& stream() { return stream_; }

 private:
  const char* file_;
  int line_;
  int priority_;
  std::ostringstream stream_;
};

}  // namespace logging
}  // namespace stoic

#define LOG(severity) \
  ::stoic::logging::LogMessage(__FILE__, __LINE__, ANDROID_LOG_##severity).stream()

// CHECK macros
#define CHECK(condition) \
  if (!(condition)) \
    LOG(FATAL) << "Check failed: " #condition " "

#define CHECK_EQ(a, b) \
  if ((a) != (b)) \
    LOG(FATAL) << "Check failed: " #a " == " #b \
               << " (" #a "=" << (a) << ", " #b "=" << (b) << ") "

#define CHECK_NE(a, b) \
  if ((a) == (b)) \
    LOG(FATAL) << "Check failed: " #a " != " #b " "

#define CHECK_LT(a, b) \
  if ((a) >= (b)) \
    LOG(FATAL) << "Check failed: " #a " < " #b " "

#define CHECK_LE(a, b) \
  if ((a) > (b)) \
    LOG(FATAL) << "Check failed: " #a " <= " #b " "

#define CHECK_GT(a, b) \
  if ((a) <= (b)) \
    LOG(FATAL) << "Check failed: " #a " > " #b " "

#define CHECK_GE(a, b) \
  if ((a) < (b)) \
    LOG(FATAL) << "Check failed: " #a " >= " #b " "
