# Reviewed dependency snapshot

Official AW baseline: e3fea3ccc69787570e282c99573298f1c3019a34. Required environment checkout: 6e4faf91094e72b9f51276b9aa394cbdc057c9a7 (two separately reviewed connection patches, not shipped here). Use its official requirements/setup in Python 3.11, not an arbitrary latest package.

Observed compatible environment: android-env 1.2.3, numpy 1.26.3, protobuf 5.29.5, grpcio 1.84.0, Pillow 12.3.0. Optional relay is independently pinned in requirements-relay.txt; these observations do not override AW's transitive requirements.

SDK/AVD requirements and official emulator setup remain those of the pinned AW README. Coati uses JDK 17 and the repository Gradle SDK/NDK configuration. Keep caches, logs, AVD and configuration outside Git. No generated protobuf or environment files are included in this PR.
