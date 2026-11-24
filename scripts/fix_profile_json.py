import json
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/config/profile.json"
text = path.read_text(encoding="utf-8")
text = text.replace("\\", "\\\\")
path.write_text(text, encoding="utf-8")

for name in ("app_config.json", "profile.json", "plan.json", "catalog.json"):
    p = path.parent / name
    json.loads(p.read_text(encoding="utf-8"))
    print("ok", name)

data = json.loads(path.read_text(encoding="utf-8"))
print("priceRegex:", data["auction"]["priceRegex"])
print("timerRegex:", data["text"]["timerRegex"])
