#!/usr/bin/env python3
"""Read-only upstream shape probe for runa周免口袋.

Offline fixtures catch regressions in our parsers; they cannot notice when Epic or Fab
changes their live responses. This probe fetches the public endpoints the app reads and
asserts only the response SHAPES our adapters depend on. It never sends cookies, account
data or orders, and never asserts on specific game titles or counts.

Usage: python tests/upstream/probe.py [--output RESULT.json]
Exit code 0 = all shapes as expected; 1 = a shape changed or an endpoint is down.
"""
import argparse
import json
import sys
import urllib.request
import urllib.error

USER_AGENT = "runa-free-pocket-upstream-probe (read-only shape check; no account data)"
EPIC_PROMOTIONS = ("https://store-site-backend-static.ak.epicgames.com/freeGamesPromotions"
                   "?locale=zh-CN&country=CN&allowCountries=CN")
EPIC_ANDROID_DISCOVER = ("https://egs-platform-service.store.epicgames.com/api/v2/public/discover"
                         "/home?count=10&country=CN&locale=en&platform=android&start=0&store=EGS")
FAB_FREE_BLADE = "https://www.fab.com/i/blades/free_content_blade"


def fetch(url, timeout=30):
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}")
        return json.loads(response.read().decode("utf-8"))


def expect(condition, message, problems):
    if not condition:
        problems.append(message)


def check_promotions(payload, problems):
    elements = payload.get("data", {}).get("Catalog", {}).get("searchStore", {}).get("elements")
    expect(isinstance(elements, list), "promotions: data.Catalog.searchStore.elements is not a list", problems)
    if not isinstance(elements, list):
        return
    for index, element in enumerate(elements):
        for field in ("id", "namespace", "title"):
            expect(isinstance(element.get(field), str) and element[field],
                   f"promotions[{index}]: missing {field}", problems)
        promotions = element.get("promotions")
        if not isinstance(promotions, dict):
            continue
        for group_name in ("promotionalOffers", "upcomingPromotionalOffers"):
            groups = promotions.get(group_name)
            if groups is None:
                continue
            expect(isinstance(groups, list), f"promotions[{index}].{group_name} is not a list", problems)
            for group in groups if isinstance(groups, list) else []:
                offers = group.get("promotionalOffers") if isinstance(group, dict) else None
                expect(isinstance(offers, list), f"promotions[{index}].{group_name}[] lacks promotionalOffers", problems)
                for offer in offers if isinstance(offers, list) else []:
                    setting = offer.get("discountSetting") if isinstance(offer, dict) else None
                    expect(isinstance(setting, dict) and isinstance(setting.get("discountPercentage"), (int, float)),
                           f"promotions[{index}].{group_name} offer lacks discountSetting.discountPercentage", problems)
                    for field in ("startDate", "endDate"):
                        expect(isinstance(offer.get(field), str),
                               f"promotions[{index}].{group_name} offer lacks {field}", problems)


def check_android_discover(payload, problems):
    data = payload.get("data")
    expect(isinstance(data, list), "android discover: data is not a list", problems)
    if not isinstance(data, list):
        return
    for index, section in enumerate(data[:20]):
        expect(isinstance(section, dict) and isinstance(section.get("type"), str),
               f"android discover[{index}]: missing type", problems)
        offers = section.get("offers") if isinstance(section, dict) else None
        if offers is not None:
            expect(isinstance(offers, list), f"android discover[{index}].offers is not a list", problems)


def check_fab_blade(payload, problems):
    # parseFab requires: isLimitedFreeContent boolean; when true, blockContentType == 'listings'
    # and tiles is a list whose entries carry listing.uid/title.
    expect(isinstance(payload, dict), "fab blade: payload is not an object", problems)
    expect(isinstance(payload.get("isLimitedFreeContent"), bool), "fab blade: isLimitedFreeContent is not boolean", problems)
    if payload.get("isLimitedFreeContent") is True:
        expect(payload.get("blockContentType") == "listings", "fab blade: blockContentType changed", problems)
        tiles = payload.get("tiles")
        expect(isinstance(tiles, list), "fab blade: tiles is not a list", problems)
        for index, tile in enumerate(tiles[:20] if isinstance(tiles, list) else []):
            listing = tile.get("listing") if isinstance(tile, dict) else None
            expect(isinstance(listing, dict) and isinstance(listing.get("uid"), str),
                   f"fab blade[{index}]: listing.uid missing", problems)


def main():
    parser = argparse.ArgumentParser(description="Read-only upstream shape probe")
    parser.add_argument("--output", help="write the JSON summary here instead of stdout only")
    arguments = parser.parse_args()

    checks = []
    failed = False

    def run(name, url, validator):
        nonlocal failed
        problems = []
        try:
            payload = fetch(url)
            validator(payload, problems)
        except (urllib.error.URLError, OSError, RuntimeError, ValueError) as error:
            problems.append(f"fetch failed: {error}")
        checks.append({"check": name, "url": url, "problems": problems})
        if problems:
            failed = True

    run("epic-promotions", EPIC_PROMOTIONS, check_promotions)
    run("epic-android-discover", EPIC_ANDROID_DISCOVER, check_android_discover)
    # Fab's blade answers 401/403 without a web session; an auth wall still means the
    # endpoint exists, so only a shape violation on 200 fails the probe.
    try:
        payload = fetch(FAB_FREE_BLADE)
        problems = []
        check_fab_blade(payload, problems)
        checks.append({"check": "fab-free-blade", "url": FAB_FREE_BLADE, "problems": problems})
        if problems:
            failed = True
    except urllib.error.HTTPError as error:
        auth_wall = error.code in (401, 403)
        checks.append({"check": "fab-free-blade", "url": FAB_FREE_BLADE,
                       "problems": [] if auth_wall else [f"unexpected HTTP {error.code}"],
                       "authWall": auth_wall})
        failed = failed or not auth_wall
    except (urllib.error.URLError, OSError, RuntimeError, ValueError) as error:
        checks.append({"check": "fab-free-blade", "url": FAB_FREE_BLADE,
                       "problems": [f"fetch failed: {error}"]})
        failed = True

    summary = {"passed": not failed, "checks": checks,
               "scope": "read-only public endpoints, no cookies, no account data, no orders"}
    text = json.dumps(summary, ensure_ascii=False, indent=2)
    if arguments.output:
        with open(arguments.output, "w", encoding="utf-8") as handle:
            handle.write(text)
    print(text)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
