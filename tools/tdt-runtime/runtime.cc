// Minimal tensor adapter. Search and all TDT state transitions remain in Python.
#include <onnxruntime_cxx_api.h>
#include <pybind11/numpy.h>
#include <pybind11/pybind11.h>
#include <pybind11/stl.h>
#include <cstring>
#include <Eigen/Core>
namespace py = pybind11;
class Session {
  Ort::Env env_{ORT_LOGGING_LEVEL_ERROR};
  Ort::SessionOptions options_;
  Ort::Session session_{nullptr};
  std::vector<std::string> inputs_, outputs_;
 public:
  Session(const std::string &path, int threads) {
    options_.SetIntraOpNumThreads(threads);
    options_.SetInterOpNumThreads(1);
    session_ = Ort::Session(env_, path.c_str(), options_);
    Ort::AllocatorWithDefaultOptions allocator;
    for (size_t i=0; i<session_.GetInputCount(); ++i)
      inputs_.emplace_back(session_.GetInputNameAllocated(i, allocator).get());
    for (size_t i=0; i<session_.GetOutputCount(); ++i)
      outputs_.emplace_back(session_.GetOutputNameAllocated(i, allocator).get());
  }
  py::list run(const std::vector<py::array> &arrays) {
    if (arrays.size()!=inputs_.size()) throw std::runtime_error("input count mismatch");
    auto mem = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
    std::vector<Ort::Value> values;
    std::vector<const char*> names, output_names;
    for (size_t i=0; i<arrays.size(); ++i) {
      const auto &a=arrays[i];
      if (!(a.flags() & py::array::c_style)) throw std::runtime_error("contiguous arrays required");
      ONNXTensorElementDataType type;
      if (a.dtype().is(py::dtype::of<float>())) type=ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT;
      else if (a.dtype().is(py::dtype::of<int64_t>())) type=ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64;
      else if (a.dtype().is(py::dtype::of<int32_t>())) type=ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32;
      else throw std::runtime_error("unsupported tensor dtype");
      std::vector<int64_t> shape(a.shape(), a.shape()+a.ndim());
      values.push_back(Ort::Value::CreateTensor(mem, const_cast<void*>(a.data()), a.nbytes(), shape.data(), shape.size(), type));
      names.push_back(inputs_[i].c_str());
    }
    for (const auto &s:outputs_) output_names.push_back(s.c_str());
    auto result=session_.Run(Ort::RunOptions{nullptr}, names.data(), values.data(), values.size(), output_names.data(), output_names.size());
    py::list output;
    for (auto &value:result) {
      auto info=value.GetTensorTypeAndShapeInfo();
      auto shape=info.GetShape();
      py::dtype dtype;
      switch (info.GetElementType()) {
        case ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT: dtype=py::dtype::of<float>(); break;
        case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32: dtype=py::dtype::of<int32_t>(); break;
        case ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64: dtype=py::dtype::of<int64_t>(); break;
        default: throw std::runtime_error("unsupported output dtype");
      }
      py::array a(dtype, shape);
      std::memcpy(a.mutable_data(), value.GetTensorRawData(), a.nbytes());
      output.append(a);
    }
    return output;
  }
};
PYBIND11_MODULE(_tdt_ort, m) {
  m.def("normalize", [](py::array_t<float, py::array::c_style> a) {
    if (a.ndim()!=2) throw std::runtime_error("expected frame matrix");
    using Mat=Eigen::Matrix<float,Eigen::Dynamic,Eigen::Dynamic,Eigen::RowMajor>;
    Eigen::Map<Mat> x(a.mutable_data(), a.shape(0), a.shape(1));
    Eigen::RowVectorXf mean=x.colwise().mean();
    Eigen::RowVectorXf var=(x.rowwise()-mean).array().square().colwise().mean();
    Eigen::RowVectorXf inv_std=(var.array().sqrt()+1e-5f).inverse();
    x.array()=(x.array().rowwise()-mean.array()).rowwise()*inv_std.array();
    return a;
  });
  m.def("version", [](){return OrtGetApiBase()->GetVersionString();});
  py::class_<Session>(m,"Session").def(py::init<const std::string&,int>()).def("run",&Session::run);
}
