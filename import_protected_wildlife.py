#!/usr/bin/env python3
"""Import the national protected wildlife catalog workbook into MySQL."""

import json
from pathlib import Path

import openpyxl
import pymysql


ROOT = Path(__file__).resolve().parent
WORKBOOK = ROOT / "国家重点保护野生动物名录.xlsx"
SETTINGS = ROOT / "config" / "settings.json"
TABLE = "protected_wildlife_catalog"


def load_rows():
    workbook = openpyxl.load_workbook(WORKBOOK, data_only=True, read_only=True)
    sheet = workbook["国家重点保护动物"]
    rows = sheet.iter_rows(values_only=True)
    for _ in range(7):
        next(rows, None)

    records = []
    for row in rows:
        if not row or not row[0]:
            continue
        serial_no, chinese_name, latin_name, taxonomy, protection_level, source = row[:6]
        records.append((
            int(serial_no),
            str(chinese_name).strip(),
            str(latin_name).strip(),
            str(taxonomy).strip(),
            str(protection_level).strip(),
            str(source).strip() if source else None,
        ))
    return records


def main():
    with SETTINGS.open(encoding="utf-8") as handle:
        db_config = json.load(handle)["db"]

    records = load_rows()
    if len(records) != 989:
        raise RuntimeError(f"Unexpected workbook row count: {len(records)}")

    connection = pymysql.connect(**db_config)
    try:
        with connection.cursor() as cursor:
            cursor.execute(f"""
                CREATE TABLE IF NOT EXISTS {TABLE} (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    serial_no INT NOT NULL,
                    chinese_name VARCHAR(255) NOT NULL,
                    latin_name VARCHAR(255) NOT NULL,
                    taxonomy VARCHAR(255) NOT NULL,
                    protection_level VARCHAR(20) NOT NULL,
                    source_url VARCHAR(1000) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_protected_wildlife_name (chinese_name, latin_name),
                    KEY idx_protected_wildlife_level (protection_level),
                    KEY idx_protected_wildlife_taxonomy (taxonomy)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
            cursor.executemany(f"""
                INSERT INTO {TABLE}
                    (serial_no, chinese_name, latin_name, taxonomy,
                     protection_level, source_url)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    serial_no = VALUES(serial_no),
                    taxonomy = VALUES(taxonomy),
                    protection_level = VALUES(protection_level),
                    source_url = VALUES(source_url)
            """, records)
            cursor.execute(
                f"SELECT COUNT(*), SUM(protection_level = 'Ⅰ级'), "
                f"SUM(protection_level = 'Ⅱ级') FROM {TABLE}"
            )
            count, level_one, level_two = cursor.fetchone()
        connection.commit()
    finally:
        connection.close()

    print({"table": TABLE, "imported": len(records), "total": count,
           "level_1": int(level_one or 0), "level_2": int(level_two or 0)})


if __name__ == "__main__":
    main()
