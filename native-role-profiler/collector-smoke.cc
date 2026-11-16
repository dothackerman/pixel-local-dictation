// Host-only public collector seam check. Never invokes a recognizer or model.
#include "role-profiler.h"
#include <cassert>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
int main(int argc, char **argv) {
  assert(argc == 3);
  std::string mode(argv[1]);
  assert(dictation_profile::Start(mode != "disabled"));
  assert(!dictation_profile::Start(true));
  auto id = dictation_profile::RegisterSession(0);
  auto lo = dictation_profile::ClockNs();
  auto native = dictation_profile::ClockNs();
  auto hi = dictation_profile::ClockNs();
  assert(lo <= native && native <= hi);
  unsigned count = mode == "overflow" ? 262145 : 4;
  if (mode == "exception") {
    try { dictation_profile::Run(0, id, []() -> int { throw std::runtime_error("synthetic"); }); }
    catch (const std::runtime_error &) {}
  } else {
    for (unsigned i = 0; i < count; ++i) {
      assert(dictation_profile::Run(0, id, [] { return 7; }) == 7);
    }
  }
  bool valid = dictation_profile::Finish(argv[2]);
  assert(valid == (mode == "disabled" || mode == "success"));
}
