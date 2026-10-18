import importlib.util
from pathlib import Path
from types import SimpleNamespace as NS
import unittest

spec = importlib.util.spec_from_file_location("mineastr_media_tests", Path(__file__).parents[1]/"mineastr_media.py")
MEDIA = importlib.util.module_from_spec(spec)
spec.loader.exec_module(MEDIA)


class AnimatedMediaTests(unittest.TestCase):
    def test_qq_market_face_survives_missing_image_component(self):
        raw = {"message": [{"type": "mface", "data": {"url": "https://qpic.cn/face.gif?key=example", "emoji_id": "face"}}]}
        self.assertEqual(MEDIA.qq_market_faces(raw), [{"type": "image", "url": "https://qpic.cn/face.gif?key=example", "name": "face.gif"}])

    def test_qq_market_faces_never_fabricate_url_or_open_local_files(self):
        self.assertEqual(MEDIA.qq_market_faces({"message": [{"type": "mface", "data": {"emoji_id": "123"}}, {"type": "mface", "data": {"url": "file:///etc/passwd"}}]}), [])

    def test_nonsegment_message_does_not_crash_media_extraction(self):
        self.assertEqual(MEDIA.qq_market_faces({"message": "plain text"}), [])
        self.assertEqual(MEDIA.discord_media(None), [])

    def test_discord_gif_attachment_without_mime_type(self):
        message = NS(attachments=[NS(content_type=None, filename="loop.GIF", url="https://cdn.discordapp.com/loop.GIF?ex=example")])
        self.assertEqual(MEDIA.discord_media(message)[0]["name"], "loop.GIF")

    def test_discord_gif_embed_preserves_animation_instead_of_thumbnail(self):
        message = NS(embeds=[NS(image=NS(url="https://media.tenor.com/loop.gif"), thumbnail=NS(url="https://media.tenor.com/preview.png"))])
        self.assertEqual(MEDIA.discord_media(message)[0]["url"], "https://media.tenor.com/loop.gif")

    def test_discord_animated_thumbnail_precedes_static_embed_image(self):
        message = NS(embeds=[NS(image=NS(url="https://example.com/static.png"), thumbnail=NS(url="https://example.com/loop.gif"))])
        self.assertEqual(MEDIA.discord_media(message)[0]["url"], "https://example.com/loop.gif")

    def test_discord_reply_embed_is_quote_preview_when_message_has_attachment(self):
        message = NS(
            reference=NS(message_id="quoted"),
            attachments=[NS(content_type="image/png", filename="new.png", url="https://cdn.discordapp.com/new.png")],
            embeds=[NS(image=NS(url="https://cdn.discordapp.com/quoted.png"), thumbnail=None)],
        )
        self.assertEqual([item["url"] for item in MEDIA.discord_media(message)], ["https://cdn.discordapp.com/new.png"])

    def test_discord_gif_sticker_and_unsupported_lottie(self):
        message = NS(stickers=[NS(format="StickerFormatType.gif", url="https://cdn.discordapp.com/face.gif"), NS(format="StickerFormatType.lottie", url="https://cdn.discordapp.com/face.json")])
        self.assertEqual(len(MEDIA.discord_media(message)), 1)

    def test_same_attachment_in_component_and_raw_event_deduplicates(self):
        image = {"type": "image", "url": "https://example.com/loop.gif", "name": "loop.gif"}
        self.assertEqual(MEDIA.unique_media([image, dict(image)]), [image])

    def test_media_bound_is_preserved(self):
        self.assertEqual(len(MEDIA.unique_media([{"url": f"https://example.com/{i}.gif"} for i in range(16)])), 8)

    def test_discord_signed_attachment_urls_share_asset_identity(self):
        first = {"url": "https://cdn.discordapp.com/attachments/10/20/one.png?ex=old&hm=a"}
        second = {"url": "https://cdn.discordapp.com/attachments/10/20/one.png?ex=new&hm=b"}
        self.assertEqual(MEDIA.unique_media([first, second]), [first])

    def test_different_discord_attachment_ids_are_kept(self):
        items = [{"url": f"https://cdn.discordapp.com/attachments/10/{identity}/same.png"} for identity in (20, 21)]
        self.assertEqual(MEDIA.unique_media(items), items)

    def test_generic_url_queries_are_not_assumed_to_be_the_same_image(self):
        items = [{"url": "https://example.com/image?id=1"}, {"url": "https://example.com/image?id=2"}]
        self.assertEqual(MEDIA.unique_media(items), items)

    def test_qq_file_handle_merges_aliases_and_preserves_lookup_metadata(self):
        first = {"url": "https://example.com/old", "qq_file": "handle.image", "name": "original"}
        second = {"url": "https://example.com/new", "qq_file": "handle.image"}
        self.assertEqual(MEDIA.unique_media([first, second]), [first])

    def test_qq_signed_download_url_deduplicates_only_the_same_file(self):
        first = {"url": "https://multimedia.nt.qq.com.cn/download?appid=1&fileid=one&spec=0&rkey=old"}
        second = {"url": "https://multimedia.nt.qq.com.cn/download?appid=1&fileid=one&spec=0&rkey=new"}
        different = {"url": "https://multimedia.nt.qq.com.cn/download?appid=1&fileid=two&spec=0&rkey=new"}
        self.assertEqual(MEDIA.unique_media([first, second, different]), [first, different])


if __name__ == "__main__":
    unittest.main()
