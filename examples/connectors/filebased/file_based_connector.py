#!/usr/bin/env python3

import json
import sys
from os import getenv

def read_db(filename):
	with open(filename) as f:
		return json.load(f)

def write_db(filename, db):
	with open(filename, 'w') as f:
		json.dump(db, f, indent=4)

def find_item_by_id(db, target_id):
	for item in db['content']:
		if item['id'] == target_id:
			return item

def do_load(filename):
	db = read_db(filename)
	ret = []
	for item in db['content']:
		item['url'] = db['url'] + item['id']
		ret.append(item)
	print(json.dumps(ret))

def do_save(filename):
	db = read_db(filename)
	j = sys.stdin.read()
	input_data = json.loads(j)
	for input_item in input_data:
		db_item = find_item_by_id(db, input_item['id'])
		for key in ["UBV", "TC", "RROE", "JS"]:
			db_item[key] = input_item[key]
	write_db(filename, db)

def main():
	_operation = getenv("operation")
	_file = getenv("file")

	if _operation == "load":
		do_load(_file)
	elif _operation == "save":
		do_save(_file)
	else:
		raise ValueError(f"Unknown operation {_operation}")

if __name__ == "__main__":
	main()
