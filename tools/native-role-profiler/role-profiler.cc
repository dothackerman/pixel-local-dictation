// Diagnostic only: absolute wall intervals around actual Ort::Session::Run.
// No tensor values, names, shapes, paths, or transcripts enter this buffer.
#include "role-profiler.h"
#include <atomic>
#include <cstdio>
#include <ctime>
#include <mutex>
#include <algorithm>
namespace dictation_profile {
namespace {
constexpr uint64_t kLimit = 262144;
constexpr uint64_t kSessionLimit = 128;
constexpr const char *kRoles[] = {"parakeet.encoder", "parakeet.decoder", "parakeet.joiner", "cohere.encoder", "cohere.decoder", "lid.encoder", "lid.decoder"};
struct Record { uint64_t session, start, end; uint32_t role, status; uint64_t reserved; };
static_assert(sizeof(Record) == 40, "fixed 10 MiB recorder");
Record records[kLimit];
struct Session { uint64_t id; uint32_t role; };
Session sessions[kSessionLimit];
std::atomic<bool> enabled{false};
std::atomic<uint64_t> attempted{0}, active{0}, failures{0}, clockFailures{0};
uint64_t sessionCount = 0;
bool configured = false;
bool wasEnabled = false;
std::mutex control;
}
int64_t ClockNs() {
  timespec t{};
  if (clock_gettime(CLOCK_MONOTONIC, &t) != 0) { ++clockFailures; return -1; }
  return int64_t(t.tv_sec) * 1000000000LL + t.tv_nsec;
}
uint64_t RegisterSession(uint32_t role) {
  std::lock_guard<std::mutex> lock(control);
  const uint64_t id = ++sessionCount;
  if (id <= kSessionLimit) sessions[id - 1] = {id, role};
  return id;
}
bool Start(bool on) {
  std::lock_guard<std::mutex> lock(control);
  if (configured || active.load() != 0) return false;
  attempted = 0; failures = 0; clockFailures = 0; sessionCount = 0;
  configured = true; wasEnabled = on; enabled.store(on);
  return true;
}
Span::Span(uint32_t role, uint64_t session) {
  if (!enabled.load(std::memory_order_relaxed)) return;
  ++active;
  if (!enabled.load()) { --active; return; }
  active_ = true;
  uint64_t i = attempted.fetch_add(1);
  if (i >= kLimit) return;
  index_ = static_cast<int64_t>(i);
  records[i] = {session, static_cast<uint64_t>(ClockNs()), 0, role, 0, 0};
}
Span::~Span() {
  if (!active_) return;
  if (failed_) ++failures;
  if (index_ >= 0) {
    auto &r = records[index_];
    r.end = static_cast<uint64_t>(ClockNs());
    r.status = failed_ ? 1 : 0;
  }
  --active;
}
bool Finish(const char *path) {
  std::lock_guard<std::mutex> lock(control);
  enabled.store(false);
  const uint64_t inFlight = active.load();
  const uint64_t count = attempted.load();
  const uint64_t kept = inFlight == 0 ? std::min(count, kLimit) : 0;
  const uint64_t dropped = count > kLimit ? count - kLimit : 0;
  const uint64_t sessionDropped = sessionCount > kSessionLimit ? sessionCount - kSessionLimit : 0;
  const bool complete = configured && inFlight == 0;
  const bool valid = complete && dropped == 0 && sessionDropped == 0 && clockFailures == 0 && failures == 0;
  FILE *f = std::fopen(path, "wb");
  if (!f) return false;
  // At most 262144 * 192 + 128 * 96 + 4096 < 64 MiB. Every string is fixed.
  std::fprintf(f, "{\"schema\":1,\"clock\":\"CLOCK_MONOTONIC\",\"enabled\":%s,\"spanLimit\":%llu,\"sessionLimit\":%llu,\"attempted\":%llu,\"retained\":%llu,\"dropped\":%llu,\"runFailures\":%llu,\"clockFailures\":%llu,\"sessionDropped\":%llu,\"activeAtFlush\":%llu,\"complete\":%s,\"valid\":%s,\"sessions\":[", wasEnabled ? "true" : "false", (unsigned long long)kLimit, (unsigned long long)kSessionLimit, (unsigned long long)count, (unsigned long long)kept, (unsigned long long)dropped, (unsigned long long)failures.load(), (unsigned long long)clockFailures.load(), (unsigned long long)sessionDropped, (unsigned long long)inFlight, complete ? "true" : "false", valid ? "true" : "false");
  for (uint64_t i = 0; i < std::min(sessionCount, kSessionLimit); ++i) {
    std::fprintf(f, "%s{\"sessionId\":%llu,\"role\":\"%s\"}", i ? "," : "", (unsigned long long)sessions[i].id, kRoles[sessions[i].role]);
  }
  std::fputs("],\"spans\":[", f);
  for (uint64_t i = 0; i < kept; ++i) {
    const auto &r = records[i];
    std::fprintf(f, "%s{\"role\":\"%s\",\"sessionId\":%llu,\"startNs\":%llu,\"endNs\":%llu,\"status\":%u}", i ? "," : "", kRoles[r.role], (unsigned long long)r.session, (unsigned long long)r.start, (unsigned long long)r.end, r.status);
  }
  std::fputs("]}", f);
  bool wrote = std::ferror(f) == 0;
  wrote = std::fclose(f) == 0 && wrote;
  return valid && wrote;
}
}
