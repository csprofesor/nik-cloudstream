# FilmKovası VidSrcMe fix

The FilmKovası `/3/` source uses `iframe[data-api="/vs_src.php?type=movie&id=..."]` and resolves a short-lived player URL at runtime. The FilmKovasi loader now resolves that data-api and uses CloudStream `WebViewResolver` to capture the final `.m3u8`/`.mp4` request.