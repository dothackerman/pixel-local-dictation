#!/usr/bin/env python3
"""Patch pinned Sherpa 1.13.8 for ORT 1.31: explicit WebGPU provider, no silent CPU
fallback, and an encoder-only provider for the NeMo transducer. Refuses unknown sites."""
import pathlib, sys

root = pathlib.Path(sys.argv[1]).resolve() / 'sherpa-onnx/csrc'


def edit(name, old, new):
    path = root / name
    text = path.read_text()
    assert text.count(old) == 1, (name, text.count(old), old[:60])  # 0 also means already applied
    path.write_text(text.replace(old, new, 1))


edit('provider.h', '  kOpenVINO = 8,  // OpenVINOExecutionProvider\n',
     '  kOpenVINO = 8,  // OpenVINOExecutionProvider\n'
     '  kWebGPU = 9,    // WebGpuExecutionProvider (Dictation patch)\n')

edit('provider.cc',
     '''  } else {
    SHERPA_ONNX_LOGE("Unsupported string: %s. Fallback to cpu", s.c_str());
    return Provider::kCPU;
  }''',
     '''  } else if (s == "webgpu") {
    return Provider::kWebGPU;
  } else {
    // Dictation patch: an unknown provider must never silently become CPU.
    SHERPA_ONNX_LOGE("Unsupported provider string: %s", s.c_str());
    SHERPA_ONNX_EXIT(-1);
  }''')

edit('session.cc',
     '''  SplitProviderAndConfig(provider_str, new_provider_str, config);

  Provider p = StringToProvider(new_provider_str);
''',
     '''  SplitProviderAndConfig(provider_str, new_provider_str, config);
  config.erase("Dictation.EncoderProvider");  // Dictation patch: read by EncoderProviderString.

  Provider p = StringToProvider(new_provider_str);
''')

edit('session.cc',
     '''    case Provider::kCPU:
      break;  // nothing to do for the CPU provider
''',
     '''    case Provider::kCPU:
      break;  // nothing to do for the CPU provider
    case Provider::kWebGPU: {
      // Dictation patch: fixed WebGPU options; absence is fatal, never CPU.
      if (std::find(available_providers.begin(), available_providers.end(),
                    "WebGpuExecutionProvider") == available_providers.end()) {
        SHERPA_ONNX_LOGE("WebGPU unavailable. Available providers: %s",
                         os.str().c_str());
        SHERPA_ONNX_EXIT(-1);
      }
      sess_opts.AppendExecutionProvider(
          "WebGPU", {{"enableGraphCapture", "0"},
                     {"enableInt64", "1"},
                     {"preferredLayout", "NCHW"},
                     {"dawnBackendType", "Vulkan"}});
      break;
    }
''')

edit('session.cc', '\n}  // namespace sherpa_onnx\n',
     '''
// Dictation patch: "cpu:<config>" whose config holds
// Dictation.EncoderProvider=webgpu|cpu yields the encoder's provider string,
// reusing the same config file. Without the key the string is unchanged.
std::string EncoderProviderString(const std::string &provider_str) {
  std::unordered_map<std::string, std::string> config;
  std::string name;
  SplitProviderAndConfig(provider_str, name, config);
  auto it = config.find("Dictation.EncoderProvider");
  if (it == config.end()) {
    return provider_str;
  }
  if (name != "cpu" || (it->second != "webgpu" && it->second != "cpu")) {
    SHERPA_ONNX_LOGE("Unsupported encoder provider %s for base provider %s",
                     it->second.c_str(), name.c_str());
    SHERPA_ONNX_EXIT(-1);
  }
  return it->second + provider_str.substr(provider_str.find(':'));
}

}  // namespace sherpa_onnx
''')

edit('session.h', 'Ort::SessionOptions GetSessionOptionsImpl(',
     '// Dictation patch: encoder-only provider; see session.cc.\n'
     'std::string EncoderProviderString(const std::string &provider_str);\n\n'
     'Ort::SessionOptions GetSessionOptionsImpl(')

name = 'offline-transducer-nemo-model.cc'
path = root / name
text = path.read_text()
assert 'encoder_sess_opts_' not in text, 'patch already applied: ' + name
init = '        sess_opts_(GetSessionOptions(config)),\n'
assert text.count(init) == 2, (name, 'initializers')
text = text.replace(init, init + '        encoder_sess_opts_(GetSessionOptionsImpl(\n'
                    '            config.num_threads, EncoderProviderString(config.provider))),\n')
assert text.count('        env_, SHERPA_ONNX_TO_ORT_PATH(config.transducer.encoder_filename),\n        sess_opts_);') == 1
# Encoder, decoder and joiner each have this line; only InitEncoder's copy changes below.
assert text.count('          env_, model_data, model_data_length, sess_opts_);\n') == 3
text = text.replace('        env_, SHERPA_ONNX_TO_ORT_PATH(config.transducer.encoder_filename),\n        sess_opts_);',
                    '        env_, SHERPA_ONNX_TO_ORT_PATH(config.transducer.encoder_filename),\n        encoder_sess_opts_);', 1)
start = text.index('  void InitEncoder(')
end = text.index('\n  }\n', start)
body = text[start:end]
assert body.count('sess_opts_);') == 1
text = text[:start] + body.replace('model_data_length, sess_opts_);', 'model_data_length, encoder_sess_opts_);') + text[end:]
member = '  Ort::SessionOptions sess_opts_;\n'
assert text.count(member) == 1
text = text.replace(member, member + '  Ort::SessionOptions encoder_sess_opts_;  // Dictation patch: encoder-only provider.\n')
path.write_text(text)
