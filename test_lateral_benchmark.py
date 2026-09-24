from PIL import Image

def analyze_opel_crossland_lateral(im, is_vehicle_semantic_confirmed=True):
    # Gate 1 & 2: If not confirmed as vehicle by semantic classifier -> reject
    if not is_vehicle_semantic_confirmed:
        return {'match': False, 'score': 0.0, 'reason': 'Not a vehicle'}
        
    w, h = im.size
    step_x = max(2, w // 40)
    step_y = max(2, h // 40)
    
    samples = []
    for y in range(0, h, step_y):
        for x in range(0, w, step_x):
            r, g, b = im.getpixel((x, y))[:3]
            lum = 0.299 * r + 0.587 * g + 0.114 * b
            max_c = max(r, max(g, b))
            min_c = min(r, min(g, b))
            sat = (max_c - min_c) / max_c if max_c > 0 else 0.0
            chroma = abs(r - g) + abs(g - b) + abs(r - b)
            samples.append({
                'x': x, 'y': y, 'r': r, 'g': g, 'b': b,
                'lum': lum, 'sat': sat, 'chroma': chroma, 'b_r': b - r
            })
            
    total = len(samples)
    if total == 0:
        return {'match': False, 'score': 0.0, 'reason': 'No samples'}
        
    # Rejeição de viaturas fortemente coloridas (azul, vermelho, amarelo, verde)
    colored_count = sum(1 for s in samples if s['sat'] > 0.38 and s['chroma'] > 48)
    colored_ratio = colored_count / total
    if colored_ratio > 0.25:
        return {'match': False, 'score': 0.0, 'reason': f'Too colored ({colored_ratio*100:.1f}%)'}
        
    mean_lum = sum(s['lum'] for s in samples) / total
    mean_br = sum(s['b_r'] for s in samples) / total
    is_daytime = (mean_lum >= 95.0 and mean_br >= -2.0) or (mean_br >= 5.0)
    
    best_score = 0.0
    best_window = None
    best_box = None
    
    # Janelas verticais adaptativas entre 35% e 75% da altura da ROI
    window_heights = [int(h * frac) for frac in (0.35, 0.42, 0.50, 0.58, 0.68)]
    
    for win_h in window_heights:
        if win_h >= h: win_h = h
        step_scan = max(3, win_h // 7)
        for y_start in range(0, h - win_h + 1, step_scan):
            y_end = y_start + win_h
            win_samples = [s for s in samples if y_start <= s['y'] < y_end]
            if len(win_samples) < 20: continue
            
            # Divisão dos 3 patamares verticais da silhueta SUV:
            # 1. Tejadilho (0% a 30% da altura da viatura)
            # 2. Carroçaria (30% a 75% da altura da viatura)
            # 3. Base / Cavas e Embaladeiras (75% a 100% da altura)
            y_roof_split = y_start + int(win_h * 0.30)
            y_base_split = y_start + int(win_h * 0.75)
            
            roof_s = [s for s in win_samples if s['y'] < y_roof_split]
            body_s = [s for s in win_samples if y_roof_split <= s['y'] < y_base_split]
            base_s = [s for s in win_samples if s['y'] >= y_base_split]
            
            if len(roof_s) < 5 or len(body_s) < 10: continue
            
            # Validação do corpo prata metálico (acromático e luminoso)
            if is_daytime:
                silver_body_px = [s for s in body_s if 105.0 <= s['lum'] <= 185.0 and s['chroma'] < 46]
                dark_body_px = [s for s in body_s if s['lum'] < 95.0]
            else:
                silver_body_px = [s for s in body_s if 75.0 <= s['lum'] <= 170.0 and (s['chroma'] < 48 or s['sat'] < 0.28)]
                dark_body_px = [s for s in body_s if s['lum'] < 65.0]
                
            silver_ratio = len(silver_body_px) / len(body_s)
            dark_ratio = len(dark_body_px) / len(body_s)
            
            # A carroçaria precisa de pelo menos 30% prata metálica
            # E rejeita viaturas pretas (onde mais de 32% do corpo é escuro)
            if silver_ratio < 0.30 or dark_ratio > 0.32:
                continue
                
            avg_body_lum = sum(s['lum'] for s in body_s) / len(body_s)
            avg_roof_lum = sum(s['lum'] for s in roof_s) / len(roof_s)
            avg_roof_br = sum(s['b_r'] for s in roof_s) / len(roof_s)
            avg_body_br = sum(s['b_r'] for s in body_s) / len(body_s)
            avg_base_lum = sum(s['lum'] for s in base_s) / len(base_s) if base_s else avg_body_lum
            
            # Assinatura do tejadilho flutuante preto / reflexo diurno do céu
            roof_score = 0.0
            if is_daytime:
                if avg_roof_br >= 16.0 and avg_roof_br > avg_body_br + 4.0:
                    roof_score = min(1.0, avg_roof_br / 28.0)
                elif avg_body_lum - avg_roof_lum >= 12.0:
                    roof_score = min(1.0, (avg_body_lum - avg_roof_lum) / 25.0)
            else:
                if avg_roof_lum <= 125.0 and avg_body_lum >= 105.0:
                    roof_score = 0.85
                elif avg_body_lum / max(1.0, avg_roof_lum) >= 1.15:
                    roof_score = min(1.0, (avg_body_lum / avg_roof_lum - 1.12) / 0.25)
                    
            if roof_score <= 0.0:
                continue
                
            # Assinatura das embaladeiras inferiores em plástico preto mate (SUV Cladding)
            base_score = 0.80
            if base_s and avg_base_lum < avg_body_lum:
                base_score = min(1.0, 0.80 + (avg_body_lum - avg_base_lum) / 40.0)
            elif base_s and avg_base_lum > avg_body_lum + 12.0:
                base_score = 0.25 # Penaliza se a base for muito mais clara que o meio
                
            # Verificação geométrica da caixa do veículo (Aspect Ratio e Envergadura Horizontal)
            silver_xs = [s['x'] for s in silver_body_px]
            if not silver_xs: continue
            min_x = min(silver_xs)
            max_x = max(silver_xs)
            box_w = max_x - min_x
            box_h = win_h
            
            aspect_ratio = box_w / box_h if box_h > 0 else 0.0
            # Um perfil lateral de crossover/SUV tem proporções 1.35 a 3.6
            if aspect_ratio < 1.35 or aspect_ratio > 3.6:
                continue
                
            # Deve ocupar pelo menos 35% da largura da janela
            if box_w < w * 0.30:
                continue
                
            body_score = min(1.0, silver_ratio / 0.48)
            total_score = 0.45 * body_score + 0.40 * roof_score + 0.15 * base_score
            
            if total_score > best_score:
                best_score = total_score
                best_window = (win_h, y_start, is_daytime, silver_ratio, dark_ratio, avg_body_lum, avg_roof_lum)
                best_box = (min_x, y_start, max_x, y_end, aspect_ratio)
                
    is_match = best_score >= 0.65
    return {
        'match': is_match,
        'score': best_score,
        'box': best_box,
        'details': best_window
    }

tests = [
    ('Day ROI (Real Full ROI)', 'real_day_roi.png', True),
    ('Night ROI (Real Full ROI)', 'real_night_roi.png', True),
    ('Day Opel Target', 'day_opel_target.png', True),
    ('Night Opel Target', 'opel_hires.png', True),
    ('Day Opel Crop', 'crop_opel_day.png', True),
    ('Night Opel Crop', 'crop_opel_night.png', True),
    ('Car Daytime Full', 'car_daytime.png', True),
    ('Day Black Car', 'day_black_car.png', True),
    ('Day Black Car Crop', 'day_black_car_crop.png', True),
    ('Day Grey Van', 'day_grey_van.png', True),
    ('Night Dark Van', 'van_crop.png', True),
    ('Night Silver Peugeot', 'car_peugeot.png', True),
    ('Night Grey Corsa', 'car_corsa.png', True),
    ('Empty Road (No Car)', 'road_crop.png', False),
    ('Room 1 (Laptop/Desk)', 'thumb_room1.png', False),
    ('Room 2 (Furniture)', 'thumb_room2.png', False)
]

print("=" * 80)
print(f"{'Target Test Image':28s} | {'Match':6s} | {'Score':6s} | Box (W, H, AR) or Reason")
print("=" * 80)
for name, p, is_veh in tests:
    res = analyze_opel_crossland_lateral(Image.open(p), is_veh)
    if res['match']:
        b = res['box']
        box_str = f"({b[2]-b[0]}x{b[3]-b[1]}, AR={b[4]:.2f})"
        print(f"{name:28s} | \033[92m{str(res['match']):6s}\033[0m | {res['score']*100:5.1f}% | {box_str}")
    else:
        reason = res.get('reason') or f"Score too low ({res['score']*100:.1f}%)"
        print(f"{name:28s} | \033[91m{str(res['match']):6s}\033[0m | {res['score']*100:5.1f}% | {reason}")
print("=" * 80)
