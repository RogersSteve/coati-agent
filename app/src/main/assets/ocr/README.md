# On-device OCR models

PaddleOCR PP-OCRv5 mobile (Simplified/Traditional Chinese, English, Japanese, pinyin) in ncnn
format, used by `bot.coati.perception.NcnnOcr` through `libcoati_ocr.so`
(`app/src/main/cpp`) on ncnn's Vulkan GPU backend. There is no CPU fallback: without a usable
Vulkan GPU, `OcrEngines.create` returns null and the app runs without OCR.

| File | Source | License |
| --- | --- | --- |
| `PP_OCRv5_mobile_det.ncnn.param` / `.bin` | https://github.com/nihui/ncnn-android-ppocrv5 (`app/src/main/assets`), pnnx conversion of PaddleOCR `PP-OCRv5_mobile_det` | Apache-2.0 (PaddleOCR) |
| `PP_OCRv5_mobile_rec.ncnn.param` / `.bin` | https://github.com/nihui/ncnn-android-ppocrv5 (`app/src/main/assets`), pnnx conversion of PaddleOCR `PP-OCRv5_mobile_rec` | Apache-2.0 (PaddleOCR) |
| `ppocrv5_dict.txt` | Generated from `app/src/main/jni/ppocrv5_dict.h` of the same project (one entry per line, C escapes removed); it is PaddleOCR's `ppocrv5_dict.txt` | Apache-2.0 (PaddleOCR) |

- PaddleOCR: https://github.com/PaddlePaddle/PaddleOCR (Apache-2.0).
- ncnn: https://github.com/Tencent/ncnn (BSD-3-Clause), prebuilt `ncnn-20260526-android-vulkan`
  downloaded at build time by `app/src/main/cpp/CMakeLists.txt`.
- The detection/recognition pipeline in `app/src/main/cpp/ppocrv5.cpp` is adapted from
  nihui/ncnn-android-ppocrv5 (Copyright (C) 2025 THL A29 Limited, a Tencent company, BSD-3-Clause).

Shapes (both models take dynamic input sizes): det input `in0` is `[3, H, W]` BGR normalized with
ImageNet mean/std, H and W multiples of 32 (long side 960 here); output `out0` is `[1, H, W]`
text probabilities. Rec input `in0` is `[3, 48, W]` BGR scaled to [-1, 1]; output `out0` is
`[T, 18385]` softmax where class 0 is the CTC blank, classes 1..18383 are the dictionary lines and
18384 is a space.

No angle classifier: UI text is upright, and boxes are axis-aligned.
