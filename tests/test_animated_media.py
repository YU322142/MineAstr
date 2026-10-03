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

    def test_discord_gif_sticker_and_unsupported_lottie(self):
        message = NS(stickers=[NS(format="StickerFormatType.gif", url="https://cdn.discordapp.com/face.gif"), NS(format="StickerFormatType.lottie", url="https://cdn.discordapp.com/face.json")])
        self.assertEqual(len(MEDIA.discord_media(message)), 1)

    def test_same_attachment_in_component_and_raw_event_deduplicates(self):
        image = {"type": "image", "url": "https://example.com/loop.gif", "name": "loop.gif"}
        self.assertEqual(MEDIA.unique_media([image, dict(image)]), [image])

    def test_media_bound_is_preserved(self):
        self.assertEqual(len(MEDIA.unique_media([{"url": f"https://example.com/{i}.gif"} for i in range(16)])), 8)


if __name__ == "__main__":
    unittest.main()
