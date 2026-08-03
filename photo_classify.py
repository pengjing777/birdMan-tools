import os
import shutil
from datetime import datetime
from PIL import Image
from PIL.ExifTags import TAGS

IMAGE_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.heic', '.heif',
                    '.cr2', '.nef', '.arw', '.raw', '.tiff', '.tif',
                    '.bmp', '.gif', '.webp'}

DEFAULT_SOURCE = '/Volumes/Untitled/DCIM'

FILE_DATE_FORMAT = '%Y:%m:%d %H:%M:%S'
DISPLAY_DATE_FORMAT = '%Y-%m-%d %H:%M:%S'


def get_date_taken(filepath):
    """从EXIF获取拍摄日期，回退到文件修改时间"""
    try:
        img = Image.open(filepath)
        exif_data = img._getexif()
        if exif_data:
            for tag_id, value in exif_data.items():
                tag = TAGS.get(tag_id, tag_id)
                if tag in ('DateTimeOriginal', 'DateTimeDigitized', 'DateTime'):
                    if isinstance(value, str):
                        try:
                            return datetime.strptime(value, FILE_DATE_FORMAT)
                        except (ValueError, TypeError):
                            pass
    except Exception:
        pass

    try:
        mtime = os.path.getmtime(filepath)
        return datetime.fromtimestamp(mtime)
    except Exception:
        return None


def format_size(size):
    for unit in ['B', 'KB', 'MB', 'GB']:
        if size < 1024:
            return f"{size:.1f}{unit}"
        size /= 1024
    return f"{size:.1f}TB"


def scan_photos(source_dir):
    """扫描目录下所有图片，返回(照片列表, 错误列表)"""
    photos = []
    errors = []

    if not os.path.isdir(source_dir):
        return [], [f"目录不存在: {source_dir}"]

    for root, dirs, files in os.walk(source_dir):
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext not in IMAGE_EXTENSIONS:
                continue
            filepath = os.path.join(root, f)
            try:
                date_taken = get_date_taken(filepath)
                if date_taken:
                    date_str = date_taken.strftime('%Y-%m-%d')
                    year_str = date_taken.strftime('%Y')
                    month_str = date_taken.strftime('%m')
                else:
                    date_str = '未知日期'
                    year_str = '未知'
                    month_str = '00'

                size = os.path.getsize(filepath)
                photos.append({
                    'path': filepath,
                    'name': f,
                    'date_taken': date_taken.strftime(DISPLAY_DATE_FORMAT) if date_taken else '未知',
                    'date_str': date_str,
                    'year': year_str,
                    'month': month_str,
                    'size': size,
                    'size_str': format_size(size),
                })
            except Exception as e:
                errors.append(f"读取文件失败 {f}: {e}")

    return photos, errors


def group_by_date(photos):
    """按日期分组统计"""
    groups = {}
    for p in photos:
        key = p['date_str']
        if key not in groups:
            groups[key] = {'date': key, 'count': 0, 'total_size': 0}
        groups[key]['count'] += 1
        groups[key]['total_size'] += p['size']
    return groups


def classify_photos(source_dir, dest_base, mode='copy'):
    """执行分类整理，mode: copy / move"""
    photos, errors = scan_photos(source_dir)
    if errors and not photos:
        return None, errors

    result = {'copied': 0, 'moved': 0, 'skipped': 0, 'errors': 0, 'details': []}

    for p in photos:
        if p['date_str'] == '未知日期':
            dest_dir = os.path.join(dest_base, '未知日期')
        else:
            dt = datetime.strptime(p['date_taken'], DISPLAY_DATE_FORMAT)
            dest_dir = os.path.join(dest_base, dt.strftime('%Y'), dt.strftime('%Y-%m-%d'))

        os.makedirs(dest_dir, exist_ok=True)
        dest_path = os.path.join(dest_dir, p['name'])

        if os.path.exists(dest_path):
            name, ext = os.path.splitext(p['name'])
            counter = 1
            while os.path.exists(dest_path):
                dest_path = os.path.join(dest_dir, f"{name}_{counter}{ext}")
                counter += 1

        try:
            if mode == 'move':
                shutil.move(p['path'], dest_path)
                result['moved'] += 1
            else:
                shutil.copy2(p['path'], dest_path)
                result['copied'] += 1
            result['details'].append({
                'file': p['name'],
                'from': p['path'],
                'to': dest_path,
                'status': 'ok',
            })
        except Exception as e:
            result['errors'] += 1
            result['details'].append({
                'file': p['name'],
                'from': p['path'],
                'to': dest_path,
                'status': 'error',
                'error': str(e),
            })

    return result, errors


def scan_all_files(source_dir):
    """递归扫描目录下的所有普通文件，供原地分类功能使用。"""
    files = []
    errors = []
    if not os.path.isdir(source_dir):
        return [], [f"目录不存在: {source_dir}"]

    for root, dirs, filenames in os.walk(source_dir):
        dirs[:] = sorted(d for d in dirs if not os.path.islink(os.path.join(root, d)))
        for filename in filenames:
            filepath = os.path.join(root, filename)
            if os.path.islink(filepath):
                continue
            try:
                date_taken = get_date_taken(filepath)
                size = os.path.getsize(filepath)
                files.append({
                    'path': filepath,
                    'name': filename,
                    'date_taken': date_taken.strftime(DISPLAY_DATE_FORMAT) if date_taken else '未知',
                    'date_str': date_taken.strftime('%Y-%m-%d') if date_taken else '未知日期',
                    'size': size,
                    'size_str': format_size(size),
                })
            except Exception as e:
                errors.append(f"读取文件失败 {filename}: {e}")
    return files, errors


def classify_in_place(source_dir):
    """在源目录内按 年/月/日 移动所有文件，不改变原有分类功能。"""
    source_dir = os.path.realpath(os.path.expanduser(source_dir))
    files, errors = scan_all_files(source_dir)
    if errors and not files:
        return None, errors

    result = {'copied': 0, 'moved': 0, 'skipped': 0, 'errors': 0, 'details': []}
    for item in files:
        if item['date_str'] == '未知日期':
            dest_dir = os.path.join(source_dir, '未知日期')
        else:
            dt = datetime.strptime(item['date_taken'], DISPLAY_DATE_FORMAT)
            dest_dir = os.path.join(source_dir, dt.strftime('%Y-%m-%d'))
        os.makedirs(dest_dir, exist_ok=True)
        dest_path = os.path.join(dest_dir, item['name'])

        if os.path.realpath(item['path']) == os.path.realpath(dest_path):
            result['skipped'] += 1
            result['details'].append({'file': item['name'], 'from': item['path'],
                                      'to': dest_path, 'status': 'skipped'})
            continue

        if os.path.exists(dest_path):
            name, ext = os.path.splitext(item['name'])
            counter = 1
            while os.path.exists(dest_path):
                dest_path = os.path.join(dest_dir, f"{name}_{counter}{ext}")
                counter += 1
        try:
            shutil.move(item['path'], dest_path)
            result['moved'] += 1
            result['details'].append({'file': item['name'], 'from': item['path'],
                                      'to': dest_path, 'status': 'ok'})
        except Exception as e:
            result['errors'] += 1
            result['details'].append({'file': item['name'], 'from': item['path'],
                                      'to': dest_path, 'status': 'error', 'error': str(e)})
    return result, errors
