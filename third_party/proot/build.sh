#!/bin/bash
# Cross-builds PRoot (termux/proot 5.1.107.102, GPL-2.0) with a static talloc 2.5.0 (LGPL-3.0) for
# arm64 Android: out/libproot.so (the tracer, PIE) and out/libproot_loader.so (static loader).
set -euo pipefail
W=${WORK_DIR:?set WORK_DIR to a directory holding proot.zip and talloc.tar.gz from third_party/proot/sources}
NDK=${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT to NDK 28.2}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
export CC=$TC/aarch64-linux-android30-clang AR=$TC/llvm-ar STRIP=$TC/llvm-strip OBJCOPY=$TC/llvm-objcopy
cd $W && rm -rf src-proot talloc-2.5.0 out && mkdir -p out

# talloc: configure through its waf with cross answers, then archive the single object statically.
tar xzf talloc.tar.gz && cd talloc-2.5.0
cat > cross-answers.txt <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF
./configure --prefix=$W/talloc-inst --disable-rpath --disable-python --cross-compile --cross-answers=cross-answers.txt > ../talloc-configure.log 2>&1
make -j16 > ../talloc-make.log 2>&1
mkdir -p $W/talloc-inst/lib $W/talloc-inst/include
# talloc calls into libreplace (rep_memset_explicit), so its object goes into the archive too.
$AR rcs $W/talloc-inst/lib/libtalloc.a $(find bin -name 'talloc.c*.o' | head -1) $(find bin -path '*replace/replace.c*.o' | head -1)
cp talloc.h $W/talloc-inst/include/
cd $W

# proot: unbundled loader (its path comes from PROOT_LOADER at run time), talloc linked statically.
unzip -q proot.zip && mv proot-5.1.107.102 src-proot && cd src-proot
# NDK 28 rejects the implicit strcmp/memset declarations in this file.
sed -i "1i #include <string.h>" src/extension/ashmem_memfd/ashmem_memfd.c
# Through the environment: the Makefile appends its own -I and -l flags with +=, which a command-line
# assignment would override. Only libtalloc.a is in that directory, so -ltalloc links statically.
export CPPFLAGS="-DARG_MAX=131072 -DVERSION=\\\"5.1.107.102\\\" -I$W/talloc-inst/include"
export LDFLAGS="-L$W/talloc-inst/lib"
export PROOT_UNBUNDLE_LOADER=/coati/unbundled
make -C src -j16 CC="$CC" LD="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" proot > ../proot-make.log 2>&1 || { tail -25 ../proot-make.log; exit 1; }
cp src/proot ../out/libproot.so
cp src/loader/loader ../out/libproot_loader.so
$STRIP ../out/libproot.so ../out/libproot_loader.so
cd ../out && ls -la && file libproot.so libproot_loader.so && sha256sum libproot.so libproot_loader.so
echo BUILD_PROOT_OK
