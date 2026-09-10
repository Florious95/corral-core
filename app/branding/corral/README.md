# corral launcher artwork

Created with built-in imagegen; backend model unspecified. The user explicitly
accepted generation without identifying the backend. No GPT Image 2.5 claim is made.

`prompts.txt` records both executed prompts. The original and refinement PNGs are
unmodified generation outputs. `provenance.json` binds these assets and the shipped
foreground by SHA-256.

Run `python3 app/branding/corral/prepare_launcher.py` with Pillow to reproduce the
launcher PNG. Packaging removes stray alpha components, retains the generated C
and terminal chevron silhouettes, and fits them within Android's 66dp safe circle
on a 108dp canvas. The 432px foreground is used by both adaptive launcher variants;
Android themes its alpha through the monochrome reference. The existing dark
background remains. No launcher mask is baked into the asset.

The notification small icon retains `ic_launcher_foreground`, independently of
the new launcher artwork. Package ID, signing configuration and storage stay unchanged.
