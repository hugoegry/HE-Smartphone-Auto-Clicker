Plug and play langs
===================
Drop a new .json here to add a language. File name free, content must have:

{
  "code": "es",
  "name": "Espanol",
  "flag": "🇪🇸",
  "strings": {
    "hub_defaults_title": "...",
    "save": "Guardar"
  }
}

Missing keys fall back to en.json then to res/values/strings.xml.
Copy en.json as base, translate, done.
