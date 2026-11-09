# Third-party notices

Coati's own code is licensed as described in [LICENSE](LICENSE). The components below are separate
programs distributed with the Coati APK for its Linux workspace. They run as separate processes, keep
their own licenses, and are not covered by Coati's license.

| Component | Version | License | In the APK as | Corresponding source |
|---|---|---|---|---|
| PRoot (termux/proot) | 5.1.107.102 | GPL-2.0 | `lib/<abi>/libproot.so`, `libproot_loader.so` | [`third_party/proot/sources/proot.zip`](third_party/proot/sources/proot.zip) |
| talloc (linked statically into PRoot) | 2.5.0 | LGPL-3.0 | inside `libproot.so` | [`third_party/proot/sources/talloc.tar.gz`](third_party/proot/sources/talloc.tar.gz) |
| Alpine Linux userland | 3.22 (aarch64, x86_64) | per package; see below | `assets/sandbox/rootfs-*.tar.xz` | Alpine package sources, see below |

**PRoot and talloc.** [`third_party/proot/build.sh`](third_party/proot/build.sh) is the complete build
recipe, including the one source change made to PRoot (adding `#include <string.h>` to
`src/extension/ashmem_memfd/ashmem_memfd.c`). To replace these libraries, rebuild them with that script
and put the results in `app/src/main/jniLibs/<abi>/`. Set `TARGET=x86_64` to build for x86_64.

**Alpine packages.** [`third_party/rootfs-packages.txt`](third_party/rootfs-packages.txt) lists every
package in the root filesystem with its exact version and license, including GPL and LGPL packages such
as bash, BusyBox and GNU libraries. The packages are unmodified official Alpine 3.22 builds for each architecture. Their
build recipes and source references are in [Alpine aports](https://gitlab.alpinelinux.org/alpine/aports)
(branch `3.22-stable`). Each package's license text, where the package ships one, is in the root
filesystem under `/usr/share/licenses` or the package's documentation directory.

Anyone who received the APK may request the complete corresponding source for a GPL or LGPL component
in it, for at least three years from the date of that release, by opening an issue in this repository.
