/* Sends INQUIRY and TEST UNIT READY to /dev/sg0 through SG_IO and prints the answers. */
#include <fcntl.h>
#include <scsi/sg.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int cmd(int fd, unsigned char *cdb, int cdb_len, unsigned char *buf, int len, unsigned char *sense)
{
    struct sg_io_hdr h;
    memset(&h, 0, sizeof(h));
    h.interface_id = 'S';
    h.cmdp = cdb; h.cmd_len = cdb_len;
    h.dxferp = buf; h.dxfer_len = len;
    h.dxfer_direction = len ? SG_DXFER_FROM_DEV : SG_DXFER_NONE;
    h.sbp = sense; h.mx_sb_len = 32; h.timeout = 10000;
    if (ioctl(fd, SG_IO, &h) < 0) { perror("SG_IO"); return -1; }
    printf("  status 0x%x host 0x%x driver 0x%x resid %d sense %d\n", h.status, h.host_status, h.driver_status, h.resid, h.sb_len_wr);
    return h.status;
}

int main(int argc, char **argv)
{
    const char *dev = argc > 1 ? argv[1] : "/dev/sg0";
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0) { perror(dev); return 1; }
    unsigned char inq[6] = { 0x12, 0, 0, 0, 96, 0 }, buf[96], sense[32];
    memset(buf, 0, sizeof(buf));
    printf("INQUIRY\n");
    if (cmd(fd, inq, 6, buf, 96, sense) == 0)
        printf("  type %d vendor '%.8s' product '%.16s' revision '%.4s'\n", buf[0] & 0x1f, buf + 8, buf + 16, buf + 32);
    unsigned char tur[6] = { 0 };
    printf("TEST UNIT READY\n");
    if (cmd(fd, tur, 6, NULL, 0, sense) == 2)
        printf("  sense key %x asc %02x ascq %02x (2/3a = no disc, 6/28 = disc changed)\n", sense[2] & 0xf, sense[12], sense[13]);
    unsigned char conf[10] = { 0x46, 0x02, 0, 0, 0, 0, 0, 0, 8, 0 }; /* GET CONFIGURATION, current profile */
    printf("GET CONFIGURATION\n");
    if (cmd(fd, conf, 10, buf, 8, sense) == 0)
        printf("  current profile 0x%02x%02x (0x40 = BD-ROM, 0x10 = DVD-ROM, 0x08 = CD-ROM)\n", buf[6], buf[7]);
    close(fd);
    return 0;
}
