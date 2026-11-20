// PocketR5900 test: a moving coloured square on a changing background.
#include <stdio.h>
#include <gsKit.h>
#include <dmaKit.h>

int main(void)
{
    GSGLOBAL *gs = gsKit_init_global();
    dmaKit_init(D_CTRL_RELE_OFF, D_CTRL_MFD_OFF, D_CTRL_STS_UNSPEC, D_CTRL_STD_OFF, D_CTRL_RCYC_8, 1 << DMA_CHANNEL_GIF);
    dmaKit_chan_init(DMA_CHANNEL_GIF);
    gsKit_init_screen(gs);
    gsKit_mode_switch(gs, GS_ONESHOT);
    printf("PocketR5900 colour test running\n");

    int x = 40, y = 40, dx = 3, dy = 2;
    unsigned frame = 0;
    for (;;)
    {
        u8 r = (frame * 2) & 0xFF, b = 0x80 - ((frame) & 0x7F);
        gsKit_clear(gs, GS_SETREG_RGBAQ(r / 4, 0x20, b, 0x80, 0x00));
        gsKit_prim_sprite(gs, x, y, x + 80, y + 80, 1, GS_SETREG_RGBAQ(0xFF, 0xD0, 0x20, 0x80, 0x00));
        gsKit_prim_sprite(gs, 20, 20, 120, 30, 1, GS_SETREG_RGBAQ(0x20, 0xFF, 0x60, 0x80, 0x00));
        x += dx; y += dy;
        if (x < 0 || x + 80 > gs->Width) dx = -dx;
        if (y < 0 || y + 80 > gs->Height) dy = -dy;
        gsKit_queue_exec(gs);
        gsKit_sync_flip(gs);
        if ((frame++ % 120) == 0) printf("frame %u\n", frame);
    }
    return 0;
}
