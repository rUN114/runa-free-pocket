"""Offline anomaly samples for the upstream probe validators; no network, no device.

The probe must fail exactly where the real parsers would fail or crash. These cases
reproduce the misses found in review: a freeGame section without offers, and a
purchase field that degraded into a string both used to pass while the adapter broke.
"""
import unittest

import probe


class PromotionsShapeTest(unittest.TestCase):
    def qualifying_element(self, **overrides):
        element = {
            "id": "offer-1", "namespace": "ns-1", "title": "Sample Game",
            "description": "Sample",
            "offerMappings": [], "catalogNs": {"mappings": []},
            "items": [{"id": "ent-1", "namespace": "ns-1"}], "keyImages": [],
            "price": {"totalPrice": {"discountPrice": 0, "fmtPrice": {"originalPrice": "$9.99"}}},
            "promotions": {"promotionalOffers": [{"promotionalOffers": [
                {"startDate": "2026-01-01T00:00:00Z", "endDate": "2099-01-01T00:00:00Z",
                 "discountSetting": {"discountPercentage": 0}}]}],
                "upcomingPromotionalOffers": []},
        }
        element.update(overrides)
        return {"data": {"Catalog": {"searchStore": {"elements": [element]}}}}

    def test_schema_faithful_sample_passes(self):
        self.assert_pass(probe.check_promotions, self.qualifying_element())

    def test_qualifying_offer_without_offer_mappings_fails(self):
        payload = self.qualifying_element()
        del payload["data"]["Catalog"]["searchStore"]["elements"][0]["offerMappings"]
        self.assert_fail(probe.check_promotions, payload, "offerMappings")

    def test_qualifying_offer_without_items_or_images_fails(self):
        payload = self.qualifying_element()
        element = payload["data"]["Catalog"]["searchStore"]["elements"][0]
        del element["items"], element["keyImages"]
        self.assert_fail(probe.check_promotions, payload, "items")
        self.assert_fail(probe.check_promotions, payload, "keyImages")

    def test_qualifying_offer_without_description_fails(self):
        payload = self.qualifying_element()
        del payload["data"]["Catalog"]["searchStore"]["elements"][0]["description"]
        self.assert_fail(probe.check_promotions, payload, "description")

    def test_fmt_price_without_original_price_fails(self):
        payload = self.qualifying_element()
        payload["data"]["Catalog"]["searchStore"]["elements"][0]["price"]["totalPrice"]["fmtPrice"] = {}
        self.assert_fail(probe.check_promotions, payload, "originalPrice")

    def test_expired_or_full_price_elements_need_no_strict_fields(self):
        # The parser skips such elements before its strict reads, so absence is not drift.
        expired = self.qualifying_element()
        element = expired["data"]["Catalog"]["searchStore"]["elements"][0]
        element["promotions"]["promotionalOffers"][0]["promotionalOffers"][0]["endDate"] = "2000-01-01T00:00:00Z"
        del element["offerMappings"], element["items"], element["keyImages"], element["description"]
        self.assert_pass(probe.check_promotions, expired)

    def assert_pass(self, validator, payload):
        problems = []
        validator(payload, problems)
        self.assertEqual(problems, [])

    def assert_fail(self, validator, payload, fragment):
        problems = []
        validator(payload, problems)
        self.assertTrue(any(fragment in problem for problem in problems), problems)


class AndroidDiscoverShapeTest(unittest.TestCase):
    def free_game(self, **content_overrides):
        content = {"title": "Sample", "mapping": {"slug": "sample"},
                   "purchase": [{"purchaseType": "Claim", "price": {"decimalPrice": 0}}]}
        content.update(content_overrides)
        return {"data": [{"type": "freeGame", "offers": [{"content": content}]}]}

    def test_complete_free_game_section_passes(self):
        self.assert_pass(probe.check_android_discover, self.free_game())

    def test_free_game_without_offers_fails(self):
        payload = {"data": [{"type": "freeGame"}]}
        self.assert_fail(probe.check_android_discover, payload, "offers")

    def test_offers_not_a_list_fails(self):
        payload = {"data": [{"type": "freeGame", "offers": {"0": {}}}]}
        self.assert_fail(probe.check_android_discover, payload, "offers")

    def test_offer_without_content_fails(self):
        payload = {"data": [{"type": "freeGame", "offers": [{"id": "x"}]}]}
        self.assert_fail(probe.check_android_discover, payload, "content")

    def test_string_purchase_fails(self):
        self.assert_fail(probe.check_android_discover, self.free_game(purchase="Claim"), "purchase")

    def test_purchase_entries_without_purchase_type_fail(self):
        self.assert_fail(probe.check_android_discover, self.free_game(purchase=["Claim"]), "purchaseType")

    def test_other_sections_may_lack_offers(self):
        self.assert_pass(probe.check_android_discover, {"data": [{"type": "curated"}]})

    def assert_pass(self, validator, payload):
        problems = []
        validator(payload, problems)
        self.assertEqual(problems, [])

    def assert_fail(self, validator, payload, fragment):
        problems = []
        validator(payload, problems)
        self.assertTrue(any(fragment in problem for problem in problems), problems)


if __name__ == "__main__":
    unittest.main()
