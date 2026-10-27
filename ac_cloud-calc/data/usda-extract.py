#!/usr/bin/env python3
"""Builds app/src/main/resources/nutrition.json from the USDA FoodData Central SR Legacy CSV release.

Usage: usda-extract.py <unzipped FoodData_Central_sr_legacy_food_csv_2018-04 dir> <out nutrition.json>
Every food value is copied from the release (food_portion.csv gram_weight of the named cup portion,
food_nutrient.csv per 100 g); nothing is estimated. The food list below is a selection by FDC id, with the
exact portion modifier whose gram weight is used. A selected food missing a value aborts the run.
Source: https://fdc.nal.usda.gov/download-datasets (SR Legacy, April 2018), public domain (CC0 1.0).
"""
import csv, json, sys, os

d, out = sys.argv[1], sys.argv[2]
# id, display name, aliases, fdc_id, exact food_portion.modifier of the 1 cup portion
SELECTION = [
    ("flour_ap", "Wheat flour, all-purpose", ["flour", "plain flour", "white flour"], "168894", "cup"),
    ("flour_whole", "Wheat flour, whole-grain", ["wholemeal flour", "whole wheat flour"], "168893", "cup"),
    ("flour_rye", "Rye flour, dark", ["rye"], "168885", "cup"),
    ("cornstarch", "Cornstarch", ["corn starch", "cornflour", "maizena"], "169698", "cup"),
    ("sugar", "Sugar, granulated", ["white sugar", "caster sugar"], "169655", "cup"),
    ("sugar_brown", "Sugar, brown (packed)", ["brown sugar"], "168833", "cup packed"),
    ("sugar_powdered", "Sugar, powdered (unsifted)", ["icing sugar", "confectioners sugar"], "169656", "cup unsifted"),
    ("honey", "Honey", [], "169640", "cup"),
    ("molasses", "Molasses", ["treacle"], "168820", "cup"),
    ("salt", "Salt, table", ["table salt"], "173468", "cup"),
    ("cocoa", "Cocoa powder, unsweetened", ["cacao", "cocoa"], "169593", "cup"),
    ("butter", "Butter, salted", [], "173410", "cup"),
    ("lard", "Lard", [], "171401", "cup"),
    ("oil_olive", "Olive oil", ["olive"], "171413", "cup"),
    ("oil_soy", "Soybean oil", ["vegetable oil", "cooking oil"], "172370", "cup"),
    ("milk_whole", "Milk, whole", ["whole milk", "milk"], "171265", "cup"),
    ("milk_2pc", "Milk, reduced fat 2%", ["semi-skimmed milk"], "170870", "cup"),
    ("yogurt", "Yogurt, plain whole milk", ["yoghurt"], "171284", "cup (8 fl oz)"),
    ("water", "Water", [], "174158", "cup"),
    ("rice_white", "Rice, white long-grain, raw", ["rice"], "168877", "cup"),
    ("rice_brown", "Rice, brown long-grain, raw", ["brown rice"], "169703", "cup"),
    ("oats", "Oats, rolled, dry", ["rolled oats", "porridge oats"], "173904", "cup"),
    ("lentils", "Lentils, raw", [], "172420", "cup"),
    ("chickpeas", "Chickpeas, raw", ["garbanzo beans"], "173756", "cup"),
    ("kidney_beans", "Kidney beans, raw", ["beans"], "175193", "cup"),
    ("peanut_butter", "Peanut butter, smooth", [], "172459", "cup"),
]
NUTRIENTS = {"kcal": "1008", "protein": "1003", "fat": "1004", "carbs": "1005", "fiber": "1079"}
want = {s[3] for s in SELECTION}
desc = {r["fdc_id"]: r["description"] for r in csv.DictReader(open(os.path.join(d, "food.csv"))) if r["fdc_id"] in want}
cup = {}
for r in csv.DictReader(open(os.path.join(d, "food_portion.csv"))):
    if r["fdc_id"] in want and r["amount"] == "1":
        cup[(r["fdc_id"], r["modifier"])] = r["gram_weight"]
nut = {}
for r in csv.DictReader(open(os.path.join(d, "food_nutrient.csv"))):
    if r["fdc_id"] in want and r["nutrient_id"] in NUTRIENTS.values():
        nut[(r["fdc_id"], r["nutrient_id"])] = r["amount"]
foods = []
for fid, name, aliases, fdc, mod in SELECTION:
    f = {"id": fid, "name": name, "aliases": aliases, "fdc_id": int(fdc), "usda": desc[fdc], "portion": "1 " + mod,
         "cup_g": str(cup[(fdc, mod)]).rstrip("0").rstrip(".") if "." in cup[(fdc, mod)] else cup[(fdc, mod)]}
    for k, nid in NUTRIENTS.items():
        v = nut[(fdc, nid)]
        f[k] = v.rstrip("0").rstrip(".") if "." in v else v
    foods.append(f)
doc = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "nutrition-static.json")))
doc["foods"] = foods
open(out, "w", encoding="utf-8").write(json.dumps(doc, indent=1, ensure_ascii=False) + "\n")
print(len(foods), "foods")
