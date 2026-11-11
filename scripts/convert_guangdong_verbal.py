#!/usr/bin/env python3
"""Pair the supplied 225-question Guangdong verbal PDFs; fail on layout/count drift.
Requires pdfplumber. Source PDFs, converted bank and audit stay in private directories.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import pdfplumber

SECTIONS = {'选词填空': ('fill', 114), '中心理解': ('main', 53), '标题填入': ('title', 6),
            '词句理解': ('meaning', 3), '细节判断': ('detail', 29), '语句填空': ('sentence', 13),
            '语句排序': ('order', 6), '接语选择': ('next', 1)}


def extract(source):
    """过滤旋转水印和页码，恢复空白下划线并保存物理页号。"""
    result = []
    with pdfplumber.open(source) as doc:
        for number, raw in enumerate(doc.pages, 1):
            glyphs = []
            if raw.images:
                if number == 1 and len(raw.images) == 1 and raw.images[0]['top'] < 400 and raw.images[0]['width'] > 400:
                    pass  # 已目视核对：目录下方封面广告，不属于题目。
                else:
                    assert number == 26 and len(raw.images) == 2 and all(10 < i['width'] < 12 and 16 < i['height'] < 19 for i in raw.images), 'Unexpected image; inspect before conversion'
                    # 已目视核对：解析中的“凝聚＝凝结＋集聚”，等号与加号是小图片。
                    glyphs = [{**i, 'text': symbol} for i, symbol in zip(sorted(raw.images, key=lambda i: i['x0']), ['＝', '＋'])]
            page = raw.filter(lambda o: o['object_type'] != 'char' or
                              abs(o['matrix'][1]) < .001 and abs(o['matrix'][2]) < .001)
            for line in page.extract_text_lines(return_chars=True):
                if line['top'] > 770:
                    continue
                chars = line['chars']
                chars.extend(i for i in glyphs if abs(i['top'] - line['top']) < 10)
                for rule in page.lines:
                    if rule['height'] < .1 and 10 < rule['width'] < 300 and abs(rule['top'] - line['bottom']) < 4:
                        occupied = any(c['text'].strip() and c['x0'] < rule['x1'] - 1
                                       and c['x1'] > rule['x0'] + 1 for c in chars)
                        if not occupied:
                            chars.append({'x0': rule['x0'], 'x1': rule['x1'], 'text': '____'})
                text, previous = '', None
                for char in sorted(chars, key=lambda c: c['x0']):
                    if previous is not None and char['x0'] - previous > 3:
                        text += ' '
                    text += char['text']
                    previous = char['x1']
                result.append((number, text.strip()))
    return result


def blocks(lines, answers=False):
    """以题型及局部题号配对，避免题号重置或跨页导致串题。"""
    section, current, result = None, None, {}
    anchor = re.compile(r'^(\d+)【答案】([A-D])。?') if answers else re.compile(r'^(\d+)【([^】]+)】')
    for page, text in lines:
        heading = re.fullmatch(r'(选词填空|中心理解|标题填入|词句理解|细节判断|语句填空|语句排序|接语选择)\s*\d*', text)
        if heading:
            section, current = heading[1], None
            continue
        if re.fullmatch(r'(片段阅读|语句表达)\s*\d*', text):
            current = None
            continue
        if section is None:
            continue  # First-page table of contents precedes the first exact heading.
        match = anchor.match(text)
        if match:
            key = (section, int(match[1]))
            assert key not in result, f'Duplicate {key}'
            current = {'tag': match[2], 'lines': [], 'pages': []}
            result[key] = current
            text = text[match.end():]
        if current is not None:
            current['pages'].append(page)
            if text:
                current['lines'].append(text)
        else:
            assert not text, f'Unassigned content: {page}: {text}'
    for section, (_, count) in SECTIONS.items():
        assert [n for s, n in result if s == section] == list(range(1, count + 1)), section
    return result


def convert(questions_path, answers_path, output, audit):
    """生成带完整解析及页码来源的单份题库，不改变旧题库身份。"""
    qs, answers = blocks(extract(questions_path)), blocks(extract(answers_path), True)
    assert qs.keys() == answers.keys() and len(qs) == 225
    questions, report = [], []
    for (section, number), q in qs.items():
        a = answers[(section, number)]
        text = '\n'.join(q['lines'])
        parts = re.split(r'(?:^|\s)([A-D])[.．、]\s*', text)
        repaired = section == '中心理解' and number == 42 and parts[1::2] == list('ABC')
        if repaired:
            assert text.startswith('纪录片肩负着在国际交流中真实展现中国形象')
            # 原PDF第36–37页漏印D项；核对同题公开页后补齐，并在每题来源中注明。
            parts.extend(['D', '要选取能引起观众共鸣的纪录片主题'])
        assert parts[1::2] == list('ABCD'), (section, number, parts[1::2])
        stem = parts[0].strip()
        if section == '选词填空':
            # 原稿少量空格没有绘制横线，仍是填词位置；仅处理填词题干中的中文间隙。
            stem = re.sub(r'(?<=[\u4e00-\u9fff，。；：“”、]) +(?=[\u4e00-\u9fff，。；：“”、])', '____', stem)
            assert '_' in stem or re.search(r'（\s*）', stem), (section, number, 'Missing blank')
        explanation = '\n'.join(a['lines']).strip()
        conclusions = re.findall(r'故(?:本题)?正确答案[为是]\s*([A-D])', explanation)
        assert not conclusions or all(letter == a['tag'] for letter in conclusions), (section, number, conclusions)
        assert len(stem) > 10 and len(explanation) > 20
        options = [{'text': re.sub(r'\s*\n\s*', '', option).strip()} for option in parts[2::2]]
        assert all(o['text'] for o in options)
        pages = lambda entry: f"{min(entry['pages'])}–{max(entry['pages'])}"
        source = (f'用户提供《03-广东省考言语理解题目解析》PDF第{pages(a)}页，{section}第{number}题；'
                  f'《03-广东省考言语理解题目汇总》PDF第{pages(q)}页。非官方参考答案。')
        if repaired:
            source += '原题PDF漏印D项，依据 https://www.xingguanggongkao.com/pc/wb/quesView/id/48549.html 核对补齐（2026-11-11）。'
        question = {'id': f'gd-verbal-{SECTIONS[section][0]}-{number:03}', 'type': 'single', 'section': '言语理解',
                    'stem': f"【{q['tag']}｜{section} {number}】\n{stem}", 'options': options,
                    'answer': a['tag'], 'explanation': explanation, 'answerSource': source}
        questions.append(question)
        report.append({'id': question['id'], 'questionPages': sorted(set(q['pages'])),
                       'answerPages': sorted(set(a['pages'])), 'answer': a['tag'], 'hasConclusion': bool(conclusions)})
    root = {'formatVersion': 1, 'title': '广东省考言语理解专项（225题）',
            'sources': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [questions_path, answers_path]},
            'questions': questions}
    data = (json.dumps(root, ensure_ascii=False, indent=2) + '\n').encode()
    assert b'Sherry' not in data and '研习社' not in data.decode()
    digest = hashlib.sha256(data).hexdigest()
    output.mkdir(parents=True, exist_ok=True)
    (output / f'{digest}.json').write_bytes(data)
    audit.mkdir(parents=True, exist_ok=True)
    (audit / 'verbal-225-audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({'id': digest, 'title': root['title'], 'questionCount': 225}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('questions', type=Path)
    parser.add_argument('answers', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--audit', type=Path, required=True)
    args = parser.parse_args()
    convert(args.questions, args.answers, args.output, args.audit)
