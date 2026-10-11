cmake_minimum_required(VERSION 3.18)
project(lumenbd C)
set(BLU ${CMAKE_CURRENT_SOURCE_DIR}/libbluray-src)
file(GLOB BLU_SRC
  ${BLU}/file/dir_posix.c ${BLU}/file/dl_posix.c ${BLU}/file/file.c ${BLU}/file/file_posix.c ${BLU}/file/filesystem.c ${BLU}/file/mount.c ${BLU}/file/dirs_xdg.c ${BLU}/libbluray/bdj/bdjo_parse.c
  ${BLU}/libbluray/*.c ${BLU}/libbluray/bdnav/*.c ${BLU}/libbluray/decoders/*.c ${BLU}/libbluray/hdmv/*.c
  ${BLU}/libbluray/disc/*.c ${BLU}/util/*.c)
list(REMOVE_ITEM BLU_SRC ${BLU}/libbluray/disc/udf_fs.c)
add_library(lumenbd SHARED ${BLU_SRC} lumenbd.c stubs.c)
target_compile_definitions(lumenbd PRIVATE HAVE_CONFIG_H=1 BLURAY_VERSION_STRING="1.3.4" DLOPEN_CRYPTO_LIBS=0)
target_include_directories(lumenbd PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${BLU} ${BLU}/libbluray)
target_compile_options(lumenbd PRIVATE -O2 -fvisibility=default -Wno-unused-function -Wno-deprecated-declarations)
target_link_libraries(lumenbd log dl)
