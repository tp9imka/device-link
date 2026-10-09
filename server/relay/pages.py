from fastapi.responses import HTMLResponse

_COPY = {
    "pair": ("Link this device", "A DeviceLink pairing code was scanned. Opening the app to finish linking…",
             "The code is valid for a few minutes and can be used once."),
    "setup": ("Set up DeviceLink", "Opening the app to connect it to this relay…",
              "This link contains an enrollment token. Do not share it."),
}

_TEMPLATE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>DeviceLink</title>
<style>
:root{{color-scheme:light dark;--bg:#f6f7f9;--card:#fff;--text:#14161a;--muted:#5d6470;--accent:#2f6fed}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0f1115;--card:#181b21;--text:#eceef2;--muted:#9aa3b2;--accent:#6e9bff}}}}
body{{margin:0;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--text);
display:grid;place-items:center;min-height:100vh;padding:16px;box-sizing:border-box}}
main{{background:var(--card);border-radius:20px;padding:32px 28px;max-width:420px;width:100%;box-shadow:0 8px 30px #0002;text-align:center}}
h1{{font-size:22px;margin:12px 0 8px}}p{{color:var(--muted);margin:8px 0}}
a.button{{display:block;margin:24px 0 12px;padding:14px;border-radius:12px;background:var(--accent);color:#fff;text-decoration:none;font-weight:600}}
small{{color:var(--muted)}}.logo{{font-size:40px}}
</style></head><body><main>
<div class="logo" aria-hidden="true">&#x1F517;</div>
<h1>{title}</h1><p>{body}</p>
<a class="button" id="open" href="#">Open DeviceLink</a>
<p><small>{note} Nothing after the # in this link is sent to the server.</small></p>
<p><small>No app yet? Install DeviceLink on this device, then scan the code again.</small></p>
</main>
<script nonce="{nonce}">
(function(){{
  var target = "devicelink://{kind}?relay=" + encodeURIComponent(location.origin) + location.hash;
  document.getElementById("open").setAttribute("href", target);
  if (location.hash.length > 4) {{ location.replace(target); }}
}})();
</script></body></html>"""


def landing_page(kind, nonce):
    title, body, note = _COPY[kind]
    html = _TEMPLATE.format(title=title, body=body, note=note, nonce=nonce, kind=kind)
    return HTMLResponse(html, headers={
        "Content-Security-Policy": f"default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-{nonce}'; base-uri 'none'; form-action 'none'",
    })
