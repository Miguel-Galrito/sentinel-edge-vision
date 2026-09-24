from PIL import Image

def get_profile(img_path):
    img = Image.open(img_path).convert("RGB")
    img_res = img.resize((64, 32), Image.Resampling.BILINEAR)
    
    # 3 vertical zones:
    # Roof: rows 0..10
    # Body: rows 11..24
    # Base: rows 25..31
    roof_px = [img_res.getpixel((x, y)) for y in range(0, 11) for x in range(64)]
    body_px = [img_res.getpixel((x, y)) for y in range(11, 25) for x in range(64)]
    base_px = [img_res.getpixel((x, y)) for y in range(25, 32) for x in range(64)]
    
    def stats(px_list):
        lums = [0.299*r + 0.587*g + 0.114*b for r, g, b in px_list]
        avg_lum = sum(lums) / len(lums)
        b_r_diff = sum(b - r for r, g, b in px_list) / len(px_list)
        return avg_lum, b_r_diff
        
    r_lum, r_br = stats(roof_px)
    b_lum, b_br = stats(body_px)
    base_lum, base_br = stats(base_px)
    
    return r_lum, r_br, b_lum, b_br, base_lum

targets = [
    ("Day Opel Crossland (Target)", "day_opel_target.png"),
    ("Night Opel Crossland (Target)", "opel_hires.png"),
    ("Day Black Car", "day_black_car.png"),
    ("Night Dark Van", "van_crop.png"),
    ("Night Silver Peugeot", "car_peugeot.png"),
    ("Night Grey Corsa", "car_corsa.png"),
    ("Empty Road", "road_crop.png"),
    ("Room Suitcase", "thumb_room1.png")
]

for name, p in targets:
    r_lum, r_br, b_lum, b_br, base_lum = get_profile(p)
    print(f"{name:30s} | Roof Lum={r_lum:5.1f} (B-R={r_br:+5.1f}) | Body Lum={b_lum:5.1f} (B-R={b_br:+5.1f}) | Base Lum={base_lum:5.1f}")
