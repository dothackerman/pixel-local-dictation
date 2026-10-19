#ifndef DICTATION_ROLE_PROFILER_H_
#define DICTATION_ROLE_PROFILER_H_
#include <cstdint>
namespace dictation_profile {
uint64_t RegisterSession(uint32_t role);
class Span {
 public:
  Span(uint32_t role, uint64_t session);
  ~Span();
  void Fail() { failed_ = true; }
 private:
  int64_t index_ = -1;
  bool active_ = false;
  bool failed_ = false;
};
template <class F> auto Run(uint32_t role, uint64_t session, F &&f) -> decltype(f()) {
  Span span(role, session);
  try { return f(); } catch (...) { span.Fail(); throw; }
}
bool Start(bool enabled);
int64_t ClockNs();
bool Finish(const char *path);
}
#endif
