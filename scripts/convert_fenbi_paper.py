#!/usr/bin/env python3
"""Convert the two supplied Guangdong 90-question layouts; stop on unfamiliar structure.
Requires pdfplumber/Pillow. Original content belongs in a private, ignored output directory.
No OCR, answer solving, network access or generic PDF import is performed here.
"""
import argparse
import base64
import hashlib
import io
import json
import logging
import re
from pathlib import Path
import pdfplumber

logging.getLogger('pdfminer').setLevel(logging.ERROR)
DPI = 120
TOP, BOTTOM = 82, 788


def convert(source, year, output, audit):
    output.mkdir(parents=True, exist_ok=True)
    audit.mkdir(parents=True, exist_ok=True)
    with pdfplumber.open(source) as doc:
        height = doc.pages[0].height
        lines, figures, anchors, boundaries, groups = [], [], [], [], []
        for pno, page in enumerate(doc.pages):
            if pno == 0 or pno == len(doc.pages) - 1:
                continue  # Cover and the answer-app QR page are not questions or answers.
            for line in page.extract_text_lines():
                if line['top'] < TOP or line['bottom'] > BOTTOM:
                    continue
                line = {**line, 'page': pno, 'at': pno * height + line['top']}
                lines.append(line)
                match = re.match(r'^(\d{1,2})\.', line['text'])
                if match and line['x0'] < 61:
                    anchors.append((int(match[1]), line['at'] - 7))
                if re.match(r'^[一二三四五六七⼀⼆]\. ', line['text']):
                    boundaries.append(line['at'] - 3)
                if re.fullmatch(r'（[一二三四]）', line['text']):
                    groups.append(line['at'] - 3)
                    boundaries.append(line['at'] - 3)
            for image in page.images:
                if image['top'] >= TOP and image['bottom'] <= BOTTOM:
                    figures.append({**image, 'page': pno, 'at': pno * height + image['top']})
        assert [n for n, _ in anchors] == list(range(1, 91)), 'Question numbers must be exactly 1..90'
        assert len(groups) == 4, 'Expected four shared-material groups'
        starts = dict(anchors)
        rendered = {}
        pixels = {}
        images_by_hash = {}

        def elements(start, end):
            return ([l for l in lines if start <= l['at'] < end],
                    [f for f in figures if start <= f['at'] < end])

        def render(start, end, label):
            selected, imgs = elements(start, end)
            if label.startswith("material-"):
                selected = [l for l in selected if not re.fullmatch(r"（[一二三四]）", l["text"])]
            result = []
            for pno in sorted({x['page'] for x in selected + imgs}):
                content = [x for x in selected + imgs if x['page'] == pno]
                top = max(TOP, min(x['top'] for x in content) - 2)
                bottom = min(BOTTOM, max(x['bottom'] for x in content) + 2)
                if pno not in rendered:
                    rendered[pno] = doc.pages[pno].to_image(resolution=DPI).original.convert('RGB')
                scale = rendered[pno].width / doc.pages[pno].width
                crop = rendered[pno].crop((int(46 * scale), int(top * scale), int(561 * scale), int(bottom * scale + 1)))
                # Palette PNG keeps crisp type and chart colors without a large JPEG artifact budget.
                crop = crop.quantize(colors=128)
                buf = io.BytesIO(); crop.save(buf, 'PNG', optimize=True)
                data = buf.getvalue(); digest = hashlib.sha256(data).hexdigest()
                images_by_hash[digest] = data
                pixels[digest] = crop.width * crop.height
                crop.save(audit / f'{year}-{label}-p{pno + 1}.png')
                result.append(base64.b64encode(data).decode('ascii'))
            return result

        materials = {}
        for index, start in enumerate(groups):
            first = 71 + 5 * index
            end = starts[first]
            selected, imgs = elements(start, end)
            text = '\n'.join(l['text'] for l in selected if not re.fullmatch(r'（[一二三四]）', l['text']))
            # Preserve tables/charts and their surrounding labels together when material contains figures.
            if imgs:
                materials[first] = {'material': f'第 {first}–{first + 4} 题共用资料（原文与图表见下图）。',
                                    'materialImages': render(start, end, f'material-{first}')}
            else:
                materials[first] = {'material': text}
            assert text or imgs

        questions, report = [], []
        for number, start in anchors:
            end = starts.get(number + 1, (len(doc.pages) - 1) * height + TOP)
            end = min([end] + [b for b in boundaries if start < b < end])
            selected, imgs = elements(start, end)
            text = '\n'.join(l['text'] for l in selected)
            assert re.match(rf'^{number}\.', text), (number, text[:80])
            text = re.sub(rf'^{number}\.', '', text, count=1).strip()
            kind = 'boolean' if number <= 4 else 'multiple' if number in (9, 10) else 'single'
            if kind == 'boolean':
                assert '判断题' in text
                stem, options = text, [{'text': '正确'}, {'text': '错误'}]
            else:
                matches = list(re.finditer(r'(?<![A-Za-z0-9])([A-D])\.', text))
                assert [m[1] for m in matches] == list('ABCD'), (number, [m[1] for m in matches], text)
                stem = text[:matches[0].start()].strip()
                options = [{'text': text[m.end():matches[i + 1].start() if i < 3 else len(text)].strip()}
                           for i, m in enumerate(matches)]
                if not imgs:
                    assert all(o['text'] for o in options), (number, options)
                if kind == 'multiple':
                    assert '多选题' in stem
            q = {'id': f'gd-{year}-{number:03}', 'type': kind, 'stem': stem, 'options': options}
            if imgs:
                q['stem'] = f'第 {number} 题（含图形或公式，完整题干与选项见原题图）。'
                q['images'] = render(start, end, f'q{number:03}')
                q['options'] = [{'text': f'见原题图中 {ch} 项'} for ch in 'ABCD']
            if number >= 71:
                q.update(materials[71 + (number - 71) // 5 * 5])
            q['sourcePages'] = sorted({l['page'] + 1 for l in selected})
            questions.append(q)
            report.append({'question': number, 'type': kind, 'pages': q['sourcePages'], 'original': text,
                           'figures': len(imgs), 'materialFirst': 71 + (number - 71) // 5 * 5 if number >= 71 else None})
        total_pixels = sum(pixels.values())
        assert total_pixels <= 16_000_000, total_pixels
        bank = {'formatVersion': 1, 'title': f'{year}年广东省公务员录用考试《行测》（网友回忆版）',
                'source': source.name, 'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                'notes': '用户提供的粉笔试卷；原 PDF 未附答案解析，未推测或补写答案。', 'questions': questions}
        data = (json.dumps(bank, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
        assert len(data) <= 16 * 1024 * 1024
        digest = hashlib.sha256(data).hexdigest()
        (output / f'{digest}.json').write_bytes(data)
        (audit / f'{year}-audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
        (audit / f'{year}-text-review.txt').write_text('\n\n'.join(f"{r['question']} [{r['type']}] pages={r['pages']} figures={r['figures']}\n{r['original']}" for r in report))
        print(json.dumps({'year': year, 'questions': len(questions), 'bytes': len(data), 'uniqueImagePixels': total_pixels,
                          'imageQuestions': [r['question'] for r in report if r['figures']], 'sha256': digest}))
        return {'id': digest, 'title': bank['title'], 'questionCount': 90}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--pdf', type=Path, required=True)
    parser.add_argument('--year', type=int, choices=[2025, 2026], required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--audit', type=Path, required=True)
    args = parser.parse_args()
    entry = convert(args.pdf, args.year, args.output, args.audit)
    index = args.output / 'index.json'
    entries = json.loads(index.read_text()) if index.exists() else []
    entries = [e for e in entries if e['title'] != entry['title']] + [entry]
    index.write_text(json.dumps(sorted(entries, key=lambda e: e['title'], reverse=True), ensure_ascii=False, indent=2) + '\n')
