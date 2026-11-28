import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import pcapi  # noqa: E402
import watch as watch_mod  # noqa: E402


USER_AYUSH_ID = "9ea4a2cdbf034020a25e837e94e5cfd9"
MOVIE_JF_ID = "0fd194d4f3daad5ab66d833872876366"
MOVIE2_JF_ID = "44728f0a78e7650cac43cca04b1eb84a"
SERIES_JF_ID = "8edc05958b8d815dbf6d194b7c060eca"
SERIES2_JF_ID = "6f5a9cb9cd240155b896c4092b11c7e5"
EP1_JF_ID = "a9458badd27aa583bce4e40a62c31aeb"
EP2_JF_ID = "ae2571f29ffe9c1b45d257a56baa9878"
EP_NEXT_SERIES2_ID = "79ddcac2948204b7f0fd860ec6d930d5"


class FakeUpstreamServer:
    """Multi-purpose local mock HTTP server for Jellyfin, Seerr, Radarr, and Sonarr."""

    def __init__(self):
        self.routes = {}  # (method, path) -> (code, payload) or callable(qs) -> (code, payload)
        self.requests = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                pass

            def _handle(self, method):
                parsed = urllib.parse.urlsplit(self.path)
                qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
                outer.requests.append((method, parsed.path, qs))
                entry = outer.routes.get((method, parsed.path))
                if entry is None:
                    code, payload = 404, {"error": f"not mocked: {method} {parsed.path}"}
                elif callable(entry):
                    code, payload = entry(qs)
                else:
                    code, payload = entry
                body = json.dumps(payload).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                self._handle("GET")

            def do_POST(self):
                self._handle("POST")

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base_url = f"http://127.0.0.1:{self.httpd.server_port}"
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class WatchEndpointsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

        self.jf_srv = FakeUpstreamServer()
        self.addCleanup(self.jf_srv.close)
        self.seerr_srv = FakeUpstreamServer()
        self.addCleanup(self.seerr_srv.close)
        self.radarr_srv = FakeUpstreamServer()
        self.addCleanup(self.radarr_srv.close)
        self.sonarr_srv = FakeUpstreamServer()
        self.addCleanup(self.sonarr_srv.close)

        self.jf_key_file = os.path.join(self.tmp.name, ".jellyfin-key")
        with open(self.jf_key_file, "w") as f:
            f.write("jf-test-token\n")

        self.seerr_settings = os.path.join(self.tmp.name, "seerr-settings.json")
        with open(self.seerr_settings, "w") as f:
            json.dump({"main": {"apiKey": "seerr-test-key"}}, f)

        self.radarr_conf = os.path.join(self.tmp.name, "radarr.xml")
        with open(self.radarr_conf, "w") as f:
            f.write("<Config><ApiKey>radarrkey123</ApiKey></Config>")

        self.sonarr_conf = os.path.join(self.tmp.name, "sonarr.xml")
        with open(self.sonarr_conf, "w") as f:
            f.write("<Config><ApiKey>sonarrkey456</ApiKey></Config>")

        self.watchlist_file = os.path.join(self.tmp.name, "watchlist.json")

        self.seerr_client = pcapi.SeerrClient(
            url=self.seerr_srv.base_url,
            settings_file=self.seerr_settings,
        )
        self.arr_cfg = {
            "radarr": ("radarr", self.radarr_srv.base_url, self.radarr_conf),
            "tv-sonarr": ("sonarr", self.sonarr_srv.base_url, self.sonarr_conf),
        }
        self.service = watch_mod.WatchService(
            seerr_client_fn=lambda: self.seerr_client,
            jellyfin_url_fn=lambda: self.jf_srv.base_url,
            jellyfin_key_fn=lambda: "jf-test-token",
            arr_config_fn=lambda: self.arr_cfg,
            preferred_user_fn=lambda: "ayush",
            watchlist_store=watch_mod.WatchlistStore(path=self.watchlist_file),
        )

        self._install_default_mocks()

    def _install_default_mocks(self):
        # Jellyfin routes
        self.jf_srv.routes[("GET", "/Users")] = (200, [
            {"Name": "amrita", "Id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
            {"Name": "ayush", "Id": USER_AYUSH_ID},
        ])
        self.jf_srv.routes[("GET", f"/Users/{USER_AYUSH_ID}/Items")] = (200, {
            "TotalRecordCount": 4,
            "Items": [
                {
                    "Id": MOVIE_JF_ID,
                    "Name": "Once Upon a Time in... Hollywood",
                    "Type": "Movie",
                    "ProductionYear": 2019,
                    "CommunityRating": 7.6,
                    "Overview": "A faded television actor and his stunt double.",
                    "Genres": ["Comedy", "Drama"],
                    "ProviderIds": {"Tmdb": "466272"},
                    "BackdropImageTags": ["bd1"],
                },
                {
                    "Id": MOVIE2_JF_ID,
                    "Name": "Primetime",
                    "Type": "Movie",
                    "ProductionYear": 2026,
                    "CommunityRating": 6.7,
                    "Overview": "An investigative thriller.",
                    "Genres": ["Thriller"],
                    "ProviderIds": {"Tmdb": "1375441"},
                    "BackdropImageTags": ["bd2"],
                },
                {
                    "Id": SERIES_JF_ID,
                    "Name": "Slow Horses",
                    "Type": "Series",
                    "ProductionYear": 2022,
                    "CommunityRating": 8.2,
                    "Overview": "A team of British intelligence agents.",
                    "Genres": ["Drama", "Thriller"],
                    "ProviderIds": {"Tmdb": "95480"},
                    "BackdropImageTags": ["bd3"],
                },
                {
                    "Id": SERIES2_JF_ID,
                    "Name": "Insomniacs After School",
                    "Type": "Series",
                    "ProductionYear": 2023,
                    "CommunityRating": 8.0,
                    "Overview": "Two insomniac teens meet in an observatory.",
                    "Genres": ["Animation"],
                    "ProviderIds": {"Tmdb": "155440"},
                    "BackdropImageTags": ["bd4"],
                },
            ],
        })
        self.jf_srv.routes[("GET", f"/Users/{USER_AYUSH_ID}/Items/Resume")] = (200, {
            "Items": [
                {
                    "Id": MOVIE_JF_ID,
                    "Name": "Once Upon a Time in... Hollywood",
                    "Type": "Movie",
                    "ProductionYear": 2019,
                    "RunTimeTicks": 96_000_000_000,
                    "ProviderIds": {"Tmdb": "466272"},
                    "BackdropImageTags": ["bd1"],
                    "UserData": {"PlaybackPositionTicks": 36_000_000_000, "PlayedPercentage": 37.5},
                },
                {
                    "Id": EP1_JF_ID,
                    "Name": "Failure's Contagious",
                    "Type": "Episode",
                    "SeriesName": "Slow Horses",
                    "SeriesId": SERIES_JF_ID,
                    "ParentIndexNumber": 1,
                    "IndexNumber": 1,
                    "ProductionYear": 2022,
                    "RunTimeTicks": 30_000_000_000,
                    "ProviderIds": {"Tmdb": "1984483"},  # Episode TMDB ID (must map to Series TMDB 95480!)
                    "ImageTags": {"Primary": "ep1img"},
                    "UserData": {"PlaybackPositionTicks": 15_000_000_000, "PlayedPercentage": 50.0},
                },
            ],
        })
        self.jf_srv.routes[("GET", "/Shows/NextUp")] = (200, {
            "Items": [
                # Duplicate series (Slow Horses) should be deduped against Resume!
                {
                    "Id": EP2_JF_ID,
                    "Name": "Work Drinks",
                    "Type": "Episode",
                    "SeriesName": "Slow Horses",
                    "SeriesId": SERIES_JF_ID,
                    "ParentIndexNumber": 1,
                    "IndexNumber": 2,
                    "RunTimeTicks": 28_000_000_000,
                    "UserData": {"PlaybackPositionTicks": 0},
                },
                # Different series (Insomniacs) should be included as Next Up!
                {
                    "Id": EP_NEXT_SERIES2_ID,
                    "Name": "Canopus",
                    "Type": "Episode",
                    "SeriesName": "Insomniacs After School",
                    "SeriesId": SERIES2_JF_ID,
                    "ParentIndexNumber": 1,
                    "IndexNumber": 5,
                    "RunTimeTicks": 14_400_000_000,
                    "ParentBackdropItemId": SERIES2_JF_ID,
                    "UserData": {"PlaybackPositionTicks": 0},
                },
            ],
        })
        self.jf_srv.routes[("GET", f"/Users/{USER_AYUSH_ID}/Items/Latest")] = (200, [
            {
                "Id": MOVIE2_JF_ID,
                "Name": "Primetime",
                "Type": "Movie",
                "ProductionYear": 2026,
                "CommunityRating": 6.7,
                "Overview": "An investigative thriller.",
                "ProviderIds": {"Tmdb": "1375441"},
                "BackdropImageTags": ["bd2"],
            },
        ])
        self.jf_srv.routes[("GET", f"/Shows/{SERIES_JF_ID}/Episodes")] = (200, {
            "Items": [
                {
                    "Id": EP1_JF_ID,
                    "Name": "Failure's Contagious",
                    "ParentIndexNumber": 1,
                    "IndexNumber": 1,
                    "RunTimeTicks": 31_800_000_000,
                    "UserData": {"Played": True, "PlaybackPositionTicks": 0, "PlayedPercentage": 0.0},
                },
                {
                    "Id": EP2_JF_ID,
                    "Name": "Work Drinks",
                    "ParentIndexNumber": 1,
                    "IndexNumber": 2,
                    "RunTimeTicks": 27_000_000_000,
                    "UserData": {"Played": False, "PlaybackPositionTicks": 13_500_000_000, "PlayedPercentage": 50.0},
                },
            ],
        })

        # Seerr routes
        def seerr_list(items):
            return (200, {"page": 1, "totalPages": 3, "totalResults": len(items), "results": items})

        self.seerr_srv.routes[("GET", "/api/v1/discover/trending")] = seerr_list([
            {
                "id": 466272,
                "mediaType": "movie",
                "title": "Once Upon a Time in... Hollywood",
                "releaseDate": "2019-07-24",
                "posterPath": "/hollywood.jpg",
                "backdropPath": "/hollywood-bd.jpg",
                "voteAverage": 7.5,
                "overview": "Hollywood story.",
                "mediaInfo": {"status": 5},
            },
            {
                "id": 696506,
                "mediaType": "movie",
                "title": "Mickey 17",
                "releaseDate": "2025-03-07",
                "posterPath": "/mickey.jpg",
                "backdropPath": "/mickey-bd.jpg",
                "voteAverage": 7.1,
                "overview": "An expendable employee.",
                "mediaInfo": {"status": 3},
            },
        ])
        self.seerr_srv.routes[("GET", "/api/v1/discover/movies")] = seerr_list([
            {
                "id": 696506,
                "mediaType": "movie",
                "title": "Mickey 17",
                "releaseDate": "2025-03-07",
                "posterPath": "/mickey.jpg",
                "voteAverage": 7.1,
            },
        ])
        self.seerr_srv.routes[("GET", "/api/v1/discover/tv")] = seerr_list([
            {
                "id": 95480,
                "mediaType": "tv",
                "name": "Slow Horses",
                "firstAirDate": "2022-04-01",
                "posterPath": "/slow.jpg",
                "voteAverage": 8.2,
            },
        ])
        self.seerr_srv.routes[("GET", "/api/v1/discover/movies/upcoming")] = seerr_list([
            {
                "id": 9991,
                "mediaType": "movie",
                "title": "Future Film",
                "releaseDate": "2027-01-15",
                "posterPath": "/future.jpg",
            },
        ])
        self.seerr_srv.routes[("GET", "/api/v1/discover/movies/genre/878")] = seerr_list([
            {"id": 696506, "mediaType": "movie", "title": "Mickey 17", "posterPath": "/mickey.jpg"},
        ])
        self.seerr_srv.routes[("GET", "/api/v1/discover/movies/genre/35")] = seerr_list([
            {"id": 466272, "mediaType": "movie", "title": "Once Upon a Time in... Hollywood", "posterPath": "/h.jpg"},
        ])
        self.seerr_srv.routes[("GET", "/api/v1/discover/tv/network/213")] = seerr_list([
            {"id": 66732, "mediaType": "tv", "name": "Stranger Things", "posterPath": "/st.jpg"},
        ])
        self.seerr_srv.routes[("GET", "/api/v1/discover/tv/network/49")] = seerr_list([
            {"id": 1399, "mediaType": "tv", "name": "Game of Thrones", "posterPath": "/got.jpg"},
        ])
        self.seerr_srv.routes[("GET", "/api/v1/discover/tv/network/2552")] = seerr_list([
            {"id": 95480, "mediaType": "tv", "name": "Slow Horses", "posterPath": "/slow.jpg"},
        ])
        self.seerr_srv.routes[("GET", "/api/v1/genres/movie")] = (200, [
            {"id": 28, "name": "Action"},
            {"id": 878, "name": "Science Fiction"},
        ])
        self.seerr_srv.routes[("GET", "/api/v1/genres/tv")] = (200, [
            {"id": 18, "name": "Drama"},
            {"id": 16, "name": "Animation"},
        ])
        self.seerr_srv.routes[("GET", "/api/v1/tv/95480/season/1")] = (200, {
            "seasonNumber": 1,
            "name": "Season 1",
            "overview": "First season.",
            "episodes": [
                {
                    "episodeNumber": 1,
                    "seasonNumber": 1,
                    "name": "Failure's Contagious",
                    "overview": "River screws up a training mission.",
                    "airDate": "2022-04-01",
                    "stillPath": "/ep1.jpg",
                    "voteAverage": 8.0,
                },
                {
                    "episodeNumber": 2,
                    "seasonNumber": 1,
                    "name": "Work Drinks",
                    "overview": "Jackson Lamb investigates.",
                    "airDate": "2022-04-01",
                    "stillPath": "/ep2.jpg",
                    "voteAverage": 8.1,
                },
                {
                    "episodeNumber": 3,
                    "seasonNumber": 1,
                    "name": "Bad Tradecraft",
                    "overview": "Not yet in Jellyfin.",
                    "airDate": "2022-04-08",
                    "stillPath": "/ep3.jpg",
                    "voteAverage": 8.4,
                },
            ],
        })

        # Radarr & Sonarr queues
        self.radarr_srv.routes[("GET", "/api/v3/queue")] = (200, {
            "records": [
                {
                    "size": 10_000_000_000,
                    "sizeleft": 7_500_000_000,
                    "status": "downloading",
                    "trackedDownloadState": "downloading",
                    "timeleft": "00:35:00",
                    "movie": {
                        "tmdbId": 696506,
                        "title": "Mickey 17",
                        "year": 2025,
                        "images": [
                            {"coverType": "poster", "remoteUrl": "https://image.tmdb.org/t/p/original/mickey.jpg"},
                        ],
                    },
                },
            ],
        })
        self.sonarr_srv.routes[("GET", "/api/v3/queue")] = (200, {
            "records": [
                {
                    "size": 2_000_000_000,
                    "sizeleft": 1_000_000_000,
                    "status": "downloading",
                    "trackedDownloadState": "downloading",
                    "timeleft": "00:10:00",
                    "series": {"tmdbId": 95480, "title": "Slow Horses", "year": 2022, "images": []},
                    "episode": {"seasonNumber": 2, "episodeNumber": 1},
                },
                {
                    "size": 2_000_000_000,
                    "sizeleft": 500_000_000,
                    "status": "downloading",
                    "trackedDownloadState": "downloading",
                    "timeleft": "00:20:00",
                    "series": {"tmdbId": 95480, "title": "Slow Horses", "year": 2022, "images": []},
                    "episode": {"seasonNumber": 2, "episodeNumber": 2},
                },
            ],
        })

    def test_watch_home_aggregates_all_sections_and_dedupes_continue_watching(self):
        code, data = self.service.home(force_refresh=True)
        self.assertEqual(code, 200)
        self.assertEqual(data["degraded"], [])
        self.assertEqual(data["libraryCount"], 4)

        # Hero prefers available Jellyfin item with backdrop & overview
        self.assertIsNotNone(data["hero"])
        self.assertEqual(data["hero"]["id"], 1375441)
        self.assertEqual(data["hero"]["status"], "available")
        self.assertIn("/pc/api/jellyfin/image/", data["hero"]["backdrop"])

        # Continue Watching merges Resume + NextUp and dedupes Slow Horses by seriesId
        cw = data["continueWatching"]
        self.assertEqual(len(cw), 3)
        self.assertEqual(cw[0]["title"], "Once Upon a Time in... Hollywood")
        self.assertEqual(cw[0]["progressPct"], 37.5)
        self.assertEqual(cw[0]["id"], 466272)

        # Slow Horses resume episode must carry SERIES TMDB ID (95480), not episode TMDB ID!
        self.assertEqual(cw[1]["title"], "Slow Horses")
        self.assertEqual(cw[1]["id"], 95480)
        self.assertEqual(cw[1]["episodeLabel"], "S1 · E1")
        self.assertEqual(cw[1]["jellyfinItemId"], EP1_JF_ID)
        self.assertFalse(cw[1]["isNextUp"])

        # Insomniacs Next Up included
        self.assertEqual(cw[2]["title"], "Insomniacs After School")
        self.assertEqual(cw[2]["id"], 155440)
        self.assertEqual(cw[2]["episodeLabel"], "S1 · E5")
        self.assertTrue(cw[2]["isNextUp"])

        # Active downloads included & Sonarr multi-episode aggregated
        dls = {d["tmdbId"]: d for d in data["downloads"]}
        self.assertIn(696506, dls)
        self.assertEqual(dls[696506]["percent"], 25.0)
        self.assertEqual(dls[696506]["eta"], "35m")
        self.assertEqual(dls[696506]["poster"], "https://image.tmdb.org/t/p/w300/mickey.jpg")
        self.assertIn(95480, dls)
        self.assertEqual(dls[95480]["episodeLabel"], "2 episodes")
        self.assertEqual(dls[95480]["percent"], 62.5)
        self.assertEqual(dls[95480]["eta"], "20m")

        # Rails populated & Slow Horses enriched as available from Jellyfin library index
        rail_ids = [r["id"] for r in data["rails"]]
        self.assertIn("trending", rail_ids)
        self.assertIn("popular_movies", rail_ids)
        self.assertIn("popular_tv", rail_ids)
        self.assertIn("network_netflix", rail_ids)
        tv_rail = next(r for r in data["rails"] if r["id"] == "popular_tv")
        self.assertEqual(tv_rail["items"][0]["status"], "available")
        self.assertEqual(tv_rail["items"][0]["jellyfinItemId"], SERIES_JF_ID)

    def test_watch_home_graceful_degradation_when_jellyfin_down(self):
        self.jf_srv.routes.clear()  # All Jellyfin requests return 404 -> URLError/HTTPError
        code, data = self.service.home(force_refresh=True)
        self.assertEqual(code, 200)
        self.assertIn("jellyfin", data["degraded"])
        self.assertEqual(data["continueWatching"], [])
        self.assertEqual(data["recentlyAdded"], [])
        # Hero falls back cleanly to Trending!
        self.assertIsNotNone(data["hero"])
        self.assertEqual(data["hero"]["id"], 466272)
        self.assertGreater(len(data["rails"]), 0)

    def test_watchlist_toggle_persists_and_enriches_availability(self):
        # Add movie that IS in Jellyfin library
        code, res = self.service.update_watchlist({
            "id": 466272,
            "mediaType": "movie",
            "title": "Once Upon a Time in... Hollywood",
            "year": 2019,
            "poster": "https://image.tmdb.org/t/p/w300/hollywood.jpg",
        })
        self.assertEqual(code, 200)
        self.assertTrue(res["ok"])
        self.assertTrue(res["inWatchlist"])
        self.assertEqual(res["count"], 1)
        self.assertIn("movie:466272", res["ids"])

        # Discover watchlist returns enriched available status + jellyfinItemId
        code, disc = self.service.discover(category="watchlist")
        self.assertEqual(code, 200)
        self.assertEqual(len(disc["items"]), 1)
        self.assertEqual(disc["items"][0]["status"], "available")
        self.assertEqual(disc["items"][0]["jellyfinItemId"], MOVIE_JF_ID)
        self.assertTrue(disc["items"][0]["inWatchlist"])

        # Toggle again removes it
        code, res2 = self.service.update_watchlist({"id": 466272, "mediaType": "movie"})
        self.assertEqual(code, 200)
        self.assertFalse(res2["inWatchlist"])
        self.assertEqual(res2["count"], 0)

    def test_discover_library_and_movies_and_invalid_category(self):
        code, lib = self.service.discover(category="library", sort="title")
        self.assertEqual(code, 200)
        self.assertEqual(lib["category"], "library")
        self.assertEqual(len(lib["items"]), 4)
        self.assertTrue(all(x["status"] == "available" for x in lib["items"]))

        code, movies = self.service.discover(category="movies", page=1)
        self.assertEqual(code, 200)
        self.assertEqual(len(movies["items"]), 1)
        self.assertEqual(len(movies["genres"]), 2)

        code, bad = self.service.discover(category="unknown_cat")
        self.assertEqual(code, 400)
        self.assertIn("error", bad)

    def test_episodes_merges_seerr_metadata_with_jellyfin_availability_and_progress(self):
        code, res = self.service.episodes(95480, 1)
        self.assertEqual(code, 200)
        self.assertEqual(res["seriesJellyfinId"], SERIES_JF_ID)
        self.assertEqual(res["availableCount"], 2)
        self.assertEqual(res["totalCount"], 3)

        eps = res["episodes"]
        self.assertEqual(len(eps), 3)
        # Ep 1: available + watched
        self.assertTrue(eps[0]["available"])
        self.assertTrue(eps[0]["played"])
        self.assertEqual(eps[0]["jellyfinItemId"], EP1_JF_ID)
        self.assertEqual(eps[0]["runtimeMinutes"], 53)
        # Ep 2: available + 50% progress
        self.assertTrue(eps[1]["available"])
        self.assertFalse(eps[1]["played"])
        self.assertEqual(eps[1]["progressPct"], 50.0)
        self.assertEqual(eps[1]["positionTicks"], 13_500_000_000)
        # Ep 3: not in Jellyfin yet
        self.assertFalse(eps[2]["available"])
        self.assertIsNone(eps[2]["jellyfinItemId"])

    def test_episodes_validation_errors(self):
        code, _ = self.service.episodes("", "1")
        self.assertEqual(code, 400)
        code, _ = self.service.episodes("95480", "-1")
        self.assertEqual(code, 400)

    def test_downloads_filter_by_tmdb_and_media_type(self):
        code, all_dl = self.service.downloads()
        self.assertEqual(code, 200)
        self.assertEqual(len(all_dl["items"]), 2)

        code, one = self.service.downloads(tmdb_id_raw="696506", media_type_raw="movie")
        self.assertEqual(code, 200)
        self.assertEqual(len(one["items"]), 1)
        self.assertEqual(one["items"][0]["title"], "Mickey 17")

    def test_watch_http_auth_middleware_tailscale_and_api_token(self):
        srv = pcapi.Server(("127.0.0.1", 0), pcapi.H)
        port = srv.server_port
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)

        with mock.patch.object(pcapi, "_watch_service", return_value=self.service), \
             mock.patch.object(pcapi, "ALLOWED_LOGINS", {"ayush@example.com"}), \
             mock.patch.object(pcapi, "ALLOW_UNAUTH_LOCAL", False), \
             mock.patch.object(pcapi, "api_token_authorized", side_effect=lambda h: h.get("Authorization") == "Bearer test-api-tok"):
            # 1. Unauthorized -> 403
            req_unauth = urllib.request.Request(f"http://127.0.0.1:{port}/pc/api/watch/downloads")
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(req_unauth, timeout=5)
            self.assertEqual(ctx.exception.code, 403)

            # 2. Tailscale header -> 200
            req_ts = urllib.request.Request(
                f"http://127.0.0.1:{port}/pc/api/watch/downloads",
                headers={"Tailscale-User-Login": "ayush@example.com"},
            )
            with urllib.request.urlopen(req_ts, timeout=5) as r:
                self.assertEqual(r.status, 200)
                body = json.loads(r.read())
                self.assertEqual(len(body["items"]), 2)

            # 3. Bearer API token -> 200
            req_tok = urllib.request.Request(
                f"http://127.0.0.1:{port}/pc/api/watch/downloads",
                headers={"Authorization": "Bearer test-api-tok"},
            )
            with urllib.request.urlopen(req_tok, timeout=5) as r:
                self.assertEqual(r.status, 200)
                body = json.loads(r.read())
                self.assertEqual(len(body["items"]), 2)


if __name__ == "__main__":
    unittest.main()
