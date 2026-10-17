"""多模态图片翻译与总超时预算 / Multimodal image translation and total deadline budgeting."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    base64,
    copy,
    hashlib,
    json,
    logger,
    re,
    time,
    trim_message,
)

from .mineastr_defaults import (
    IMAGE_TRANSLATION_CACHE_LIMIT,
    IMAGE_TRANSLATION_CORRECTION_RESERVE_SECONDS,
    IMAGE_TRANSLATION_PROTOCOL_BUDGET_SECONDS,
    TRANSLATION_PROMPT_MAX_LENGTH,
)


class ImageTranslationService:
    """多模态图片翻译与总超时预算 / Multimodal image translation and total deadline budgeting.

    图片缓存归本服务；模型调用和修正共用同一请求截止时间。
    Own the image cache; initial model calls and correction share one request deadline.
    """

    def __init__(self, plugin):
        self.plugin = plugin
        self._image_translation_cache = {}
        self._image_translation_cache_accessed_at = {}

    async def _translate_image_request(self, request: dict[str, Any]) -> dict[str, Any]:
        """Translate an external image through AstrBot's multimodal provider."""

        if not self.plugin._cfg_bool("game_translation_enabled"):
            logger.info(
                "MineAstr 图片翻译请求被跳过：bridge_settings.game_translation_enabled=false"
            )
            return {}
        encoded = str(request.get("image_base64") or "").strip()
        if encoded.startswith("data:") and ";base64," in encoded:
            encoded = encoded.split(";base64,", 1)[1].strip()
        if not encoded or len(encoded) > 1_000_000:
            return {}
        try:
            image_bytes = base64.b64decode(encoded, validate=True)
        except (ValueError, TypeError):
            logger.warning("MineAstr 图片翻译请求包含无效的 Base64 图片。")
            return {}
        if not image_bytes or len(image_bytes) > 768 * 1024:
            logger.warning("MineAstr 图片翻译请求超过 768 KiB 安全上限。")
            return {}
        mime_type = str(request.get("mime_type") or "image/jpeg").strip().lower()
        if not re.fullmatch(r"[a-z0-9.+-]+/[a-z0-9.+-]+", mime_type):
            mime_type = "image/jpeg"

        languages = self.plugin._translation_languages(
            request.get("target_languages") or self.plugin._game_translation_languages()
        )
        if not languages:
            return {}
        prompt_instructions = trim_message(
            str(
                request.get("prompt")
                or self.plugin._cfg("image_translation_prompt")
                or ""
            ).strip(),
            TRANSLATION_PROMPT_MAX_LENGTH,
        )
        context = trim_message(str(request.get("context") or "").strip(), 2000)
        configured_timeout = float(
            max(1, min(60, self.plugin._cfg_int("game_translation_timeout_seconds")))
        )
        total_budget = min(
            IMAGE_TRANSLATION_PROTOCOL_BUDGET_SECONDS,
            configured_timeout
            + min(IMAGE_TRANSLATION_CORRECTION_RESERVE_SECONDS, configured_timeout),
        )
        correction_reserve = min(
            IMAGE_TRANSLATION_CORRECTION_RESERVE_SECONDS,
            configured_timeout,
            max(1.0, total_budget / 3.0),
        )
        glossary_max_chars = self.plugin._bounded_cfg_int(
            "image_translation_glossary_max_chars", 0, 12_000
        )
        show_original = self.plugin._cfg_bool("game_translation_show_original")
        max_relay_length = self.plugin._cfg_int("max_relay_length")
        origin = f"minecraft://{str(request.get('server_id') or 'minecraft')}"
        provider_ids = self.plugin._translation_provider_ids(origin)
        glossary_token = await self.plugin._translation_glossary_cache_token()
        cache_key: tuple[Any, ...] = (
            hashlib.sha256(image_bytes).hexdigest(),
            languages,
            prompt_instructions,
            context,
            glossary_max_chars,
            show_original,
            max_relay_length,
            provider_ids,
        )
        if glossary_token is not None:
            cache_key += (glossary_token,)
        accessed_at = getattr(self.plugin, "_image_translation_cache_accessed_at", None)
        if not isinstance(accessed_at, dict):
            accessed_at = {}
            self._image_translation_cache_accessed_at = accessed_at
        cached = self._image_translation_cache.get(cache_key)
        if cached is not None:
            accessed_at[cache_key] = time.monotonic()
            return copy.deepcopy(cached)

        request_deadline = self.plugin._translation_clock() + total_budget
        try:
            prompt = json.dumps(
                {
                    "target_languages": list(languages),
                    "context": context,
                    "task": "从图片中识别所有可见文字并翻译",
                },
                ensure_ascii=False,
            )
            system_prompt = (
                "You are a strict multimodal image translation engine. Read visible text "
                "from the supplied image, preserve line breaks when they are meaningful, "
                "and translate into every requested locale. Do not describe the image or "
                "add explanations. Return only JSON in this exact shape: "
                '{"source_language":"locale","source_text":"recognized text",'
                '"translations":{"target_locale":"translated text"}}. '
                "Use normalized lowercase locale codes and omit a translation whose "
                "language is the same as the detected source language."
            )
            if prompt_instructions:
                system_prompt += (
                    "\nApply these image-translation instructions while keeping the JSON "
                    "shape: " + json.dumps(prompt_instructions, ensure_ascii=False)
                )
            remaining = request_deadline - self.plugin._translation_clock()
            initial_timeout = min(
                configured_timeout,
                max(0.1, remaining - correction_reserve),
            )
            response = await self.plugin._translation_response(
                prompt,
                system_prompt,
                languages,
                origin,
                image_urls=[f"data:{mime_type};base64,{encoded}"],
                timeout_seconds=initial_timeout,
            )
            raw = getattr(response, "completion_text", "")
            result = self.plugin._parse_translation_response(
                raw, languages, max_relay_length
            )
            parsed_source = ""
            match = re.search(r"\{.*\}", str(raw or "").strip(), flags=re.DOTALL)
            if match:
                try:
                    parsed = json.loads(match.group(0))
                    if isinstance(parsed, dict):
                        parsed_source = trim_message(
                            parsed.get("source_text") or parsed.get("ocr_text"),
                            max_relay_length,
                        )
                except (json.JSONDecodeError, TypeError):
                    pass
            result["source_text"] = parsed_source
            if not result.get("translations") and not parsed_source:
                raise RuntimeError("多模态模型没有返回有效的图片翻译 JSON")
            if parsed_source:
                glossary_instructions = await self.plugin._append_translation_glossary(
                    prompt_instructions,
                    parsed_source,
                    image=True,
                )
                if glossary_instructions != prompt_instructions:
                    remaining = (
                        request_deadline - self.plugin._translation_clock() - 0.25
                    )
                    if remaining > 0.1:
                        corrected = await self.plugin._translate_text(
                            parsed_source,
                            languages,
                            f"minecraft://{str(request.get('server_id') or 'minecraft')}",
                            custom_instructions=glossary_instructions,
                            cache_scope="game-image-ocr",
                            context=(("image-context", context),) if context else (),
                            inject_glossary=False,
                            timeout_seconds=min(
                                configured_timeout,
                                IMAGE_TRANSLATION_CORRECTION_RESERVE_SECONDS,
                                remaining,
                            ),
                        )
                        corrected_source, corrected_translations = (
                            self.plugin._translation_result_parts(corrected)
                        )
                        if corrected_translations:
                            initial_source, initial_translations = (
                                self.plugin._translation_result_parts(result)
                            )
                            merged_translations = dict(initial_translations)
                            merged_translations.update(corrected_translations)
                            result["source_language"] = (
                                corrected_source or initial_source
                            )
                            result["translations"] = merged_translations
                            result["source_text"] = parsed_source
            result["show_original"] = show_original
            self._image_translation_cache[cache_key] = copy.deepcopy(result)
            accessed_at[cache_key] = time.monotonic()
            while len(self._image_translation_cache) > IMAGE_TRANSLATION_CACHE_LIMIT:
                oldest = next(iter(self._image_translation_cache))
                self._image_translation_cache.pop(oldest, None)
                accessed_at.pop(oldest, None)
            return result
        except Exception as exc:
            logger.warning("MineAstr 图片多模态翻译失败：%s", exc)
            return {}
