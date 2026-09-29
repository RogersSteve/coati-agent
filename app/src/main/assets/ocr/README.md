# On-device OCR models

PaddleOCR PP-OCRv4 mobile (Chinese + English), converted to ONNX, used by
`bot.coati.perception.PaddleOcr` on ONNX Runtime.

| File | Source | License |
| --- | --- | --- |
| `ch_PP-OCRv4_det_infer.onnx` | https://huggingface.co/SWHL/RapidOCR/tree/main/PP-OCRv4 (RapidOCR conversion of PaddleOCR `ch_PP-OCRv4_det_infer`) | Apache-2.0 |
| `ch_PP-OCRv4_rec_infer.onnx` | https://huggingface.co/SWHL/RapidOCR/tree/main/PP-OCRv4 (RapidOCR conversion of PaddleOCR `ch_PP-OCRv4_rec_infer`) | Apache-2.0 |
| `ppocr_keys_v1.txt` | https://github.com/PaddlePaddle/PaddleOCR/blob/release/2.7/ppocr/utils/ppocr_keys_v1.txt | Apache-2.0 |

PaddleOCR: https://github.com/PaddlePaddle/PaddleOCR (Apache-2.0).
RapidOCR: https://github.com/RapidAI/RapidOCR (Apache-2.0).

Shapes: det input `x` is `[N, 3, H, W]` (H, W multiples of 32), output `[N, 1, H, W]`
probabilities. Rec input `x` is `[N, 3, 48, W]`, output `[N, T, 6625]` softmax where class 0 is
the CTC blank, classes 1..6623 are the dictionary lines and 6624 is a space.

The angle classifier is not bundled: UI text is upright.
