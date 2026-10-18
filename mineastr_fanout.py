"""会话路由与并行平台投递 / Session routing and concurrent platform delivery."""

from __future__ import annotations

from .mineastr_plugin_runtime import (
    Any,
    Image,
    MessageChain,
    Plain,
    _RELAY_DIAGNOSTICS,
    asyncio,
    logger,
    parse_items,
    time,
    trim_message,
    unique_media,
)

from .mineastr_defaults import (
    _ACTIVE_RELAY_SESSIONS,
)


class PlatformFanout:
    """会话路由与并行平台投递 / Session routing and concurrent platform delivery.

    会话集合归本组件；原文投递不等待共享翻译，各平台失败单独处理。
    Own session membership; original-text delivery does not await shared translation and failures are isolated per target.
    """

    def __init__(self, plugin):
        self.plugin = plugin
        self._relay_sessions = set()

    def _refresh_relay_sessions(self) -> None:
        _ACTIVE_RELAY_SESSIONS.difference_update(self._relay_sessions)
        self._relay_sessions = set(parse_items(self.plugin._cfg("relay_sessions")))
        _ACTIVE_RELAY_SESSIONS.update(self._relay_sessions)

    def _set_relay_sessions(self, sessions: set[str]) -> None:
        self.plugin._set_cfg("relay_sessions", "\n".join(sorted(sessions)))
        self.plugin._save_plugin_config()
        self.plugin._refresh_relay_sessions()

    def _game_chat_platform(self, origin: str) -> str:
        """A bot reply keeps the recipient's origin platform, including custom adapter IDs."""
        platform_id = str(origin or "").split(":", 1)[0].casefold()
        if platform_id == "minecraft":
            return "minecraft"
        for key, family in (
            ("qq_notification_settings", "qq"),
            ("discord_notification_settings", "discord"),
        ):
            profile = self.plugin._cfg(key)
            if isinstance(profile, dict) and platform_id in {
                item.casefold() for item in parse_items(profile.get("platform_ids"))
            }:
                return family
        for adapter in self.plugin._platform_instances():
            try:
                if str(adapter.meta().id).casefold() != platform_id:
                    continue
                if self.plugin._is_discord_adapter(adapter):
                    return "discord"
                if self.plugin._is_qq_adapter(adapter):
                    return "qq"
            except Exception:
                continue
        if platform_id in {"default", "qq", "aiocqhttp", "aqqbot", "qq_official"}:
            return "qq"
        if platform_id in {"discord", "dc"}:
            return "discord"
        return "minecraft"

    def _relay_route_targets(self, origin: str) -> set[str] | None:
        configured = str(self.plugin._cfg("relay_routes") or "").strip()
        if not configured:
            return None
        origin_candidates = self.plugin._session_channel_candidates(origin)
        if not origin_candidates:
            return set()
        matched_targets: set[str] = set()
        matched = False
        for raw_line in configured.splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            if "<->" in line:
                parts = [part.strip() for part in line.split("<->") if part.strip()]
                if len(parts) < 2:
                    continue
                endpoints = [item for part in parts for item in parse_items(part)]
                endpoint_candidates = {
                    candidate
                    for endpoint in endpoints
                    for candidate in self.plugin._session_channel_candidates(endpoint)
                }
                if origin_candidates.isdisjoint(endpoint_candidates):
                    continue
                matched = True
                matched_targets.update(
                    endpoint
                    for endpoint in endpoints
                    if self.plugin._session_channel_candidates(endpoint).isdisjoint(
                        origin_candidates
                    )
                )
                continue
            if "=>" in line:
                source, targets = line.split("=>", 1)
                source_candidates = self.plugin._session_channel_candidates(
                    source.strip()
                )
                if origin_candidates.isdisjoint(source_candidates):
                    continue
                matched = True
                matched_targets.update(parse_items(targets))
        if not matched:
            return set()
        return matched_targets

    def _relay_target_sessions(
        self, exclude: str = "", source_platform: str = ""
    ) -> list[str]:
        candidates = [
            session
            for session in sorted(self._relay_sessions)
            if session != exclude
            and (
                not source_platform
                or self.plugin._session_platform_id(session).casefold()
                != source_platform.casefold()
            )
        ]
        route_targets = self.plugin._relay_route_targets(exclude)
        if route_targets is None:
            return candidates
        allowed: set[str] = set()
        for target in route_targets:
            allowed.update(self.plugin._session_channel_candidates(target))
        return [
            session
            for session in candidates
            if not self.plugin._session_channel_candidates(session).isdisjoint(allowed)
        ]

    def _platform_translation_languages_for_sessions(
        self, sessions: list[str]
    ) -> list[str]:
        languages: list[str] = []
        for session in sessions:
            profile = self.plugin._platform_notification_profile(
                self.plugin._session_platform_id(session), session
            )
            if profile is None or not bool(profile.get("chat_translation_enabled")):
                continue
            for language in self.plugin._translation_languages(
                profile.get("chat_translation_languages")
            ):
                if language not in languages:
                    languages.append(language)
        return languages

    async def _translate_relay_message(
        self,
        content: str,
        sessions: list[str],
        origin: str,
        *,
        include_game: bool = False,
        extra_languages: tuple[str, ...] = (),
        cache_scope: str = "",
        detect_bilingual_equivalence: bool = False,
    ) -> dict[str, Any]:
        languages = self.plugin._platform_translation_languages_for_sessions(sessions)
        _RELAY_DIAGNOSTICS.info(
            "Relay language selection: origin=%s targets=%s languages=%s",
            origin,
            sessions,
            languages,
        )
        if include_game and self.plugin._cfg_bool("game_translation_enabled"):
            for language in self.plugin._game_translation_languages():
                if language not in languages:
                    languages.append(language)
        for language in extra_languages:
            normalized = self.plugin._normalize_translation_language(language)
            if normalized and normalized not in languages:
                languages.append(normalized)
        if not languages:
            return {}
        resolved_scope = cache_scope or f"relay-unified:{origin}"
        context = self.plugin._translation_context_snapshot(origin, resolved_scope)
        translation_started = time.monotonic()
        result = await self.plugin._translate_text(
            content,
            tuple(languages),
            origin,
            custom_instructions=str(
                self.plugin._cfg("translation_custom_instructions") or ""
            ),
            cache_scope=resolved_scope,
            context=context,
            detect_bilingual_equivalence=detect_bilingual_equivalence,
        )
        _RELAY_DIAGNOSTICS.info(
            "Relay translation timing: origin=%s translation_seconds=%.3f",
            origin,
            time.monotonic() - translation_started,
        )
        self.plugin._remember_translation_context(origin, resolved_scope, content)
        return result

    async def _platform_chat_message(
        self,
        session: str,
        content: str,
        origin: str = "",
        *,
        translation_result: dict[str, Any] | None = None,
    ) -> str:
        profile = self.plugin._platform_notification_profile(
            self.plugin._session_platform_id(session), session
        )
        if profile is None or not bool(profile.get("chat_translation_enabled")):
            return content
        languages = self.plugin._translation_languages(
            profile.get("chat_translation_languages")
        )
        result = translation_result
        if result is None:
            cache_scope = f"platform-unified:{origin or session}"
            result = await self.plugin._translate_text(
                content,
                languages,
                origin,
                custom_instructions=str(
                    self.plugin._cfg("translation_custom_instructions") or ""
                ),
                cache_scope=cache_scope,
                context=self.plugin._translation_context_snapshot(
                    origin or session, cache_scope
                ),
            )
            self.plugin._remember_translation_context(
                origin or session, cache_scope, content
            )
        source_language, translations = self.plugin._translation_result_parts(result)
        if not translations and not any(
            self.plugin._same_translation_language(source_language, language)
            for language in languages
        ):
            return content
        messages: list[str] = []
        original_in_targets = False
        for language in languages:
            if self.plugin._same_translation_language(source_language, language):
                original_in_targets = True
            elif language in translations:
                translated = translations[language]
                if self.plugin._same_translation_text(content, translated):
                    original_in_targets = True
                    continue
                messages.append(translated)
        if not messages:
            return content
        if (
            bool(profile.get("chat_translation_show_original"))
            and not original_in_targets
        ):
            messages.append(f"[原文/Original] {content}")
        return "\n".join(dict.fromkeys(message for message in messages if message))

    def _platform_translation_only(
        self,
        session: str,
        content: str,
        translation_result: dict[str, Any] | None,
    ) -> str:
        """Render a quoted fragment without adding an original-text label."""

        profile = self.plugin._platform_notification_profile(
            self.plugin._session_platform_id(session), session
        )
        if profile is None or not bool(profile.get("chat_translation_enabled")):
            return content
        languages = self.plugin._translation_languages(
            profile.get("chat_translation_languages")
        )
        source_language, translations = self.plugin._translation_result_parts(
            translation_result
        )
        messages: list[str] = []
        for language in languages:
            if self.plugin._same_translation_language(source_language, language):
                continue
            translated = translations.get(language)
            if not translated or self.plugin._same_translation_text(
                content, translated
            ):
                continue
            messages.append(translated)
        return "\n".join(dict.fromkeys(messages)) or content

    async def _send_to_relay_sessions(
        self,
        content: str,
        *,
        exclude: str = "",
        source_platform: str = "",
        sessions: list[str] | None = None,
        translation_result: dict[str, Any] | None = None,
        media: list[dict[str, str]] | None = None,
        reply_context: dict[str, str] | None = None,
        reply_translation_result: dict[str, Any] | None = None,
    ) -> None:
        target_sessions = sessions
        if target_sessions is None:
            target_sessions = self.plugin._relay_target_sessions(
                exclude, source_platform
            )
        translation_origin = exclude or (target_sessions[0] if target_sessions else "")
        result = translation_result
        if result is None:
            result = await self.plugin._translate_relay_message(
                content,
                target_sessions,
                translation_origin,
            )

        reply_result = reply_translation_result or {}
        quoted_text = str((reply_context or {}).get("text") or "").strip()
        if quoted_text and target_sessions and reply_translation_result is None:
            reply_result = await self.plugin._translate_relay_message(
                quoted_text,
                target_sessions,
                translation_origin,
            )

        async def send_session(session: str) -> None:
            started = time.monotonic()
            message = await self.plugin._platform_chat_message(
                session,
                content,
                translation_result=result,
            )
            session_reply_context = reply_context
            if quoted_text and reply_result:
                translated_quote = self.plugin._platform_translation_only(
                    session,
                    quoted_text,
                    reply_result,
                )
                if translated_quote != quoted_text:
                    session_reply_context = dict(reply_context or {})
                    session_reply_context["text"] = translated_quote
            if media or session_reply_context:
                await self.plugin._send_to_relay_session(
                    session,
                    message,
                    media=media,
                    reply_context=session_reply_context,
                )
            else:
                await self.plugin._send_to_relay_session(session, message)
            _RELAY_DIAGNOSTICS.info(
                "Relay destination finished: session=%s transport_seconds=%.3f",
                session,
                time.monotonic() - started,
            )

        await asyncio.gather(
            *(send_session(session) for session in dict.fromkeys(target_sessions))
        )

    async def _optimized_platform_media(
        self, media: list[dict[str, str]]
    ) -> list[dict[str, str]]:
        optimizer = getattr(self.plugin, "_image_optimizer", None)
        if optimizer is None:
            return media

        async def prepare(item):
            reference = str(item.get("url") or "").strip()
            try:
                converted = await optimizer.prepare(reference)
                return {**item, "url": str(converted[0])} if converted else item
            except Exception as exc:
                logger.warning(
                    "MineAstr oversized-media preparation failed: %s",
                    type(exc).__name__,
                )
                return None

        results = await asyncio.gather(*(prepare(item) for item in media[:8]))
        # 不同下载地址可能归一化为同一缓存文件，投递前再去重一次。
        # Distinct source URLs can normalize to the same cached image; attach that file once.
        return unique_media([item for item in results if item is not None])

    async def _send_to_relay_session(
        self,
        session: str,
        content: str,
        *,
        media: list[dict[str, str]] | None = None,
        reply_context: dict[str, str] | None = None,
    ) -> None:
        message = trim_message(content, self.plugin._cfg_int("max_relay_length"))
        media = unique_media(media or [])
        if media and getattr(self.plugin, "_image_optimizer", None) is not None:
            # Start normalization concurrently, deliver text first, then attach the completed media.
            task = asyncio.create_task(
                self.plugin._optimized_platform_media(media),
                name="mineastr-platform-media",
            )
            try:
                # A Discord reply preview is itself visual content. Sending a
                # quote-only message first would make the referenced image
                # appear separately from the attachment. Keep replies and
                # their media in one platform message; ordinary media-only
                # messages still retain early text delivery.
                if (message or reply_context) and not reply_context:
                    await self.plugin._send_to_relay_session(
                        session, content, reply_context=reply_context
                    )
                    message = ""
                    reply_context = None
                media = await task
            finally:
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)
        chain: list[Any] = []
        outbound_texts: list[str] = []
        if reply_context:
            quoted_sender = str(reply_context.get("sender") or "").strip()
            quoted_text = str(reply_context.get("text") or "").strip()
            if quoted_sender or quoted_text:
                quote = "↪ "
                if quoted_sender:
                    quote += quoted_sender
                if quoted_text:
                    quote += f": {quoted_text}" if quoted_sender else quoted_text
                chain.append(Plain(quote + "\n"))
                outbound_texts.append(quote)
        if message:
            chain.append(Plain(message))
            outbound_texts.append(message)
        for item in media or ():
            reference = str(item.get("url") or "").strip()
            if not reference:
                continue
            try:
                filename = str(item.get("name") or "image").strip() or "image"
                if reference.startswith(("http://", "https://")):
                    chain.append(Image.fromURL(reference, filename=filename))
                else:
                    chain.append(Image(file=reference, filename=filename))
            except Exception:
                logger.debug("MineAstr 忽略无效图片引用：%s", reference)
        if not chain:
            return
        send_started = time.monotonic()
        try:
            sent = await self.plugin.context.send_message(session, MessageChain(chain))
            _RELAY_DIAGNOSTICS.info(
                "Platform send result: session=%s accepted=%s send_seconds=%.3f",
                session,
                bool(sent),
                time.monotonic() - send_started,
            )
            if not sent:
                logger.warning("MineAstr 找不到桥接会话：%s", session)
            else:
                logger.debug(
                    "MineAstr 桥接消息已发送：platform=%s quote=%s media=%s",
                    self.plugin._session_platform_id(session),
                    bool(reply_context),
                    len(media or ()),
                )
                for outbound_text in outbound_texts:
                    self.plugin._store_outbound_relay_message(session, outbound_text)
        except Exception as exc:
            logger.warning("MineAstr 向桥接会话 %s 发送消息失败：%s", session, exc)
