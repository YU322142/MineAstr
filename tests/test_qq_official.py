"""Official QQ capability boundaries and opaque-ID routing; no live sends."""
from types import SimpleNamespace as NS
import unittest
from unittest.mock import AsyncMock

from test_minecraft_protocol import MessageChain
from test_z_discord_automation import MAIN
from test_platform_image_dedup import Image, MEDIA
from unittest.mock import patch


class OfficialQQTests(unittest.IsolatedAsyncioTestCase):
    def plugin(self, kind="qq_official"):
        plugin = MAIN.MineAstrPlugin.__new__(MAIN.MineAstrPlugin)
        adapter = NS(meta=lambda: NS(name=kind, id="official-example"), get_client=lambda: NS())
        plugin.context = NS(get_platform_inst=lambda identity: adapter if identity == "official-example" else None,
                            platform_manager=NS(platform_insts=[adapter]), send_message=AsyncMock(return_value=True))
        plugin.config = {"qq_settings": {"qq_auto_group_card": True, "qq_auto_unbind_on_leave": True,
                                         "qq_notification_settings": {"platform_ids": "default", "enabled": True}}}
        return plugin, adapter

    def test_custom_official_ids_use_qq_profile_and_sender_platform(self):
        for kind in ("qq_official", "qq_official_webhook"):
            plugin, adapter = self.plugin(kind)
            self.assertTrue(plugin._is_qq_adapter(adapter))
            self.assertEqual(plugin._game_chat_platform("official-example:GroupMessage:opaque-group"), "qq")
            self.assertTrue(plugin._platform_notification_profile("official-example")["enabled"])
            plugin._attach_qq_listeners()  # No attempted OneBot subscriptions.
            self.assertEqual(plugin._qq_listener_bindings, {})

    async def test_official_card_update_is_an_explicit_capability_skip(self):
        plugin, _ = self.plugin()
        event = NS(get_platform_name=lambda: "qq_official")
        result = await plugin._update_qq_group_card_after_bind(event, NS())
        self.assertTrue(result["skipped"])
        self.assertEqual(result["reason"], "official_group_card_unsupported")

    def test_official_attachments_use_original_https_url_once(self):
        plugin, _ = self.plugin()
        raw = NS(group_openid="opaque-group", author=NS(member_openid="opaque-member"),
                 attachments=[{"url": "//example.com/original.gif", "content_type": "image/gif"}])
        with patch.object(MEDIA, "Image", Image):
            media = plugin._event_media(NS(message_obj=NS(raw_message=raw, message=[Image(file="local-copy.gif")])))
        self.assertEqual(len(media), 1)
        self.assertEqual(media[0]["url"], "https://example.com/original.gif")
        self.assertNotIn("qq_file", media[0])

    def test_openids_are_never_inferred_from_legacy_qq_numbers(self):
        plugin, _ = self.plugin()
        event = NS(get_platform_id=lambda: "official-example", get_sender_id=lambda: "opaque-member",
                   get_sender_name=lambda: "")
        identity = plugin._identity(event)
        self.assertEqual(identity["owner_key"], "official-example:opaque-member")

    def test_official_direct_message_dict_normalizes_original_attachment(self):
        plugin, _ = self.plugin()
        raw = {"author": {"user_openid": "opaque-user"}, "attachments": [
            {"url": "example.com/photo.png", "content_type": "image/png"},
            {"url": "file:///private/image.png", "content_type": "image/png"},
            {"url": "//example.com/video.mp4", "content_type": "video/mp4"},
        ]}
        with patch.object(MEDIA, "Image", Image):
            media = plugin._event_media(NS(message_obj=NS(raw_message=raw, message=[Image(file="copy.png")])))
        self.assertEqual(media, [{"type": "image", "url": "https://example.com/photo.png", "name": "photo.png"}])

    def test_routes_preserve_opaque_session_identity(self):
        plugin, _ = self.plugin()
        official = "official-example:GroupMessage:MixedCaseOpenID"
        discord = "discord:GroupMessage:example-channel"
        plugin.config["bridge_settings"] = {"relay_sessions": official + "\n" + discord,
                                             "relay_routes": official + " <-> " + discord}
        plugin._refresh_relay_sessions()
        self.assertEqual(plugin._relay_target_sessions(exclude=official), [discord])
        self.assertEqual(plugin._relay_target_sessions(exclude=discord), [official])
