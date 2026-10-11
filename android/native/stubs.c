/* The disc's file system is read by the app (Udf.kt); libbluray's own UDF reader is not built. */
#include <stddef.h>
void *udf_image_open(const char *img_path, void *read_blocks_handle, void *read_blocks) { return NULL; }
void  udf_image_close(void *udf) { }
const char *udf_volume_id(void *udf) { return NULL; }
void *udf_file_open(void *udf, const char *filename) { return NULL; }
void *udf_dir_open(void *udf, const char *dirname) { return NULL; }

/* No Java: BD-J discs are reported as such and not started. */
#define NO_BDJ 1
void *bdj_open(const char *path, void *bd, const char *bdj_disc_id, void *storage) { return NULL; }
void bdj_close(void *bdjava) { }
int bdj_process_event(void *bdjava, unsigned ev, unsigned param) { return 0; }
int bdj_jvm_available(void *storage) { return 0; }
void bdj_config_cleanup(void *storage) { }
