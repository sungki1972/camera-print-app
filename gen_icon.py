from PIL import Image, ImageDraw
import os

def draw_printer_icon(size):
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size
    pad = int(s * 0.08)

    # round square background
    d.rounded_rectangle([pad, pad, s - pad, s - pad],
                        radius=int(s * 0.18),
                        fill=(25, 118, 210))  # #1976D2

    cx, cy = s // 2, s // 2

    # --- printer body ---
    bw = int(s * 0.54)  # body width
    bh = int(s * 0.26)  # body height
    bx1 = cx - bw // 2
    by1 = cy - int(s * 0.06)
    bx2 = cx + bw // 2
    by2 = by1 + bh
    d.rounded_rectangle([bx1, by1, bx2, by2], radius=int(s * 0.04), fill=(255, 255, 255))

    # --- paper input tray (top) ---
    tw = int(s * 0.38)
    th = int(s * 0.14)
    tx1 = cx - tw // 2
    ty1 = by1 - th + int(s * 0.02)
    tx2 = cx + tw // 2
    ty2 = by1 + int(s * 0.02)
    d.rounded_rectangle([tx1, ty1, tx2, ty2], radius=int(s * 0.02), fill=(227, 242, 253))  # light blue

    # --- output paper (bottom) ---
    pw = int(s * 0.38)
    ph = int(s * 0.18)
    px1 = cx - pw // 2
    py1 = by2 - int(s * 0.04)
    px2 = cx + pw // 2
    py2 = py1 + ph
    d.rounded_rectangle([px1, py1, px2, py2], radius=int(s * 0.02), fill=(227, 242, 253))

    # paper lines (text representation)
    line_color = (144, 202, 249)  # lighter blue
    line_h = max(1, int(s * 0.015))
    for i in range(3):
        ly = py1 + int(s * 0.05) + i * int(s * 0.04)
        lx1 = px1 + int(s * 0.06)
        lx2 = px2 - int(s * 0.06)
        if ly + line_h < py2 - int(s * 0.02):
            d.rounded_rectangle([lx1, ly, lx2, ly + line_h], radius=1, fill=line_color)

    # --- power button on printer body ---
    dot_r = max(2, int(s * 0.025))
    dot_x = bx2 - int(s * 0.08)
    dot_y = by1 + bh // 2
    d.ellipse([dot_x - dot_r, dot_y - dot_r, dot_x + dot_r, dot_y + dot_r],
              fill=(76, 175, 80))  # green dot

    # --- small camera icon (top-left corner) ---
    cam_s = int(s * 0.13)
    cam_x = cx - int(s * 0.12)
    cam_y = ty1 - int(s * 0.10)
    # camera body
    d.rounded_rectangle([cam_x, cam_y + cam_s // 4, cam_x + cam_s, cam_y + cam_s],
                        radius=int(s * 0.02), fill=(255, 255, 255))
    # camera lens
    lens_r = max(1, int(s * 0.025))
    lens_cx = cam_x + cam_s // 2
    lens_cy = cam_y + cam_s // 2 + cam_s // 8
    d.ellipse([lens_cx - lens_r, lens_cy - lens_r, lens_cx + lens_r, lens_cy + lens_r],
              fill=(25, 118, 210))
    # camera top bump
    bump_w = int(s * 0.05)
    d.rectangle([cam_x + cam_s // 3, cam_y, cam_x + cam_s // 3 + bump_w, cam_y + cam_s // 4 + 1],
                fill=(255, 255, 255))

    # --- arrow from camera to printer ---
    arrow_x = cam_x + cam_s + int(s * 0.02)
    arrow_y = cam_y + cam_s // 2 + cam_s // 8
    arrow_end = bx1 + int(s * 0.04)
    arrow_mid_y = by1 + bh // 3
    # diagonal line
    line_w = max(2, int(s * 0.02))
    d.line([(arrow_x, arrow_y), (arrow_end + int(s*0.03), arrow_mid_y)],
           fill=(255, 255, 255, 200), width=line_w)
    # arrowhead
    ah = max(3, int(s * 0.04))
    ax = arrow_end + int(s*0.03)
    ay = arrow_mid_y
    d.polygon([(ax, ay - ah), (ax + ah, ay), (ax, ay + ah)], fill=(255, 255, 255, 200))

    return img


densities = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

base = os.path.expanduser('~/apps/camera-print-app/app/src/main/res')

for folder, sz in densities.items():
    out_dir = os.path.join(base, folder)
    os.makedirs(out_dir, exist_ok=True)
    icon = draw_printer_icon(sz)
    icon.save(os.path.join(out_dir, 'ic_launcher.png'))
    round_icon = icon.copy()
    round_icon.save(os.path.join(out_dir, 'ic_launcher_round.png'))
    print(f'{folder}: {sz}x{sz} saved')

print('Done!')
