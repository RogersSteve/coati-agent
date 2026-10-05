# Debug OCR input precheck

`OCR_BENCH` keeps external app storage as its default. Set `--es input_storage internal` to select `files/ocr_bench` for uploads through `run-as bot.coati`, readable by the ordinary app UID. This does not alter the core OCR engine, GPU selection, recognition or system permissions.

The receiver rejects unknown storage, missing or unlistable directories, an empty image set, image-named directories and unreadable files before expensive Vulkan initialization. Supported extensions are JPEG/JPG/PNG, case-insensitive, sorted by name. Input precheck does not prove an image decodes or OCR succeeds. Failure logs end with `OCR bench done`.

Example after an explicitly authorized input upload:

```sh
adb -H 127.0.0.1 -P "$PRIVATE_ADB_PORT" -s "$SERIAL" shell am broadcast \
  -a bot.coati.debug.OCR_BENCH -n bot.coati/.debug.DebugAgentReceiver \
  --es input_storage internal --ei repeat 1
```

The receiver, helper and benchmark entry point are Debug-only; Release contains none of them. Ordinary JVM tests validate directory selection and failure cases without starting OCR.
