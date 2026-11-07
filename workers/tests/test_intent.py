from beatmind_workers import intent

PROMPT = "Hindi romantic song for the wedding first night, Bhojpuri music vibe with orchestra, duet"
ROMANTIC = "[Verse 1]\nतेरी आँखों में सपने हैं\nदिल की धड़कन तेरे नाम\n[Chorus]\nसाजन मेरे पास आ"
ROMANTIC_LATIN = "[Verse 1]\nteri aankhon mein sapne hain\ndil ki dhadkan tere naam"


def test_a_plan_that_fits_has_no_problems():
    assert intent.problems(ROMANTIC, "romantic bhojpuri duet", PROMPT, "hi", []) == []
    assert intent.problems(ROMANTIC_LATIN, "romantic duet", PROMPT, "hi", []) == []


def test_empty_and_instrumental_plans_are_rejected():
    assert intent.problems("", "", PROMPT, "hi", []) == ["the plan has no lyrics to sing"]
    assert intent.problems("[Instrumental]", "", PROMPT, "hi", []) == ["the plan has no lyrics to sing"]


def test_lyrics_in_the_wrong_script_are_rejected():
    chinese = intent.problems("[Verse]\n你好 世界 我爱你", "", PROMPT, "hi", [])
    assert chinese[0].startswith("lyrics are not in Hindi")
    assert intent.problems(ROMANTIC, "", "summer pop", "en", [])[0].startswith("lyrics are not in Latin")


def test_a_devotional_plan_for_a_romantic_request_is_rejected():
    found = intent.problems("[Verse]\nजय शिव शंकर\nहरि का नाम", "devotional bhajan", PROMPT, "hi", [])
    assert any("made it devotional" in p for p in found)
    assert any("lyrics are devotional" in p for p in found)


def test_one_everyday_name_is_not_devotional():
    lyrics = "[Verse]\nram ki shaadi mein\ndil nache"
    assert intent.problems(lyrics, "wedding pop", PROMPT, "hinglish", []) == []


def test_devotional_requests_may_be_devotional():
    lyrics = "[Verse]\nजय शिव शंकर\nहरि का नाम"
    assert intent.problems(lyrics, "devotional bhajan", "Shiv bhajan", "hi", []) == []


def test_a_second_plan_that_repeats_the_first_is_rejected():
    assert intent.problems(ROMANTIC, "", PROMPT, "hi", [ROMANTIC])[0].startswith("the lyrics repeat take 1")
    other = "[Verse 1]\nचाँदनी रात सुहानी है\nमहकी हवा चले"
    assert intent.problems(other, "", PROMPT, "hi", [ROMANTIC]) == []


def test_stage_directions_and_leaked_tokens_are_not_sung():
    # Seen on the Mac: "[abrupt silence]", "[laughing]" and "<|audio_code_2246|>" in plan lyrics.
    raw = "[Verse 1]\n<|audio_code_2246|>teri yaad aayi\n[laughing]\n[Chorus]\ndil mera\n[abrupt silence]"
    assert intent.clean(raw) == "[Verse 1]\nteri yaad aayi\n[Chorus]\ndil mera"


def test_lyrics_written_for_a_longer_song_are_cut_at_a_section():
    lyrics = "\n".join(
        ["[Verse 1]", "a", "b", "c", "d", "[Chorus]", "e", "f", "g", "h", "[Verse 2]", "i", "j", "k", "l"]
    )
    # Planned for 180 s, sung in 120 s: two of three sections fit.
    assert intent.fit(lyrics, 180, 120) == "\n".join(lyrics.splitlines()[:10])
    assert intent.fit(lyrics, 125, 120) == lyrics  # close enough: kept whole
    assert intent.fit(lyrics, None, 120) == lyrics
    assert intent.fit(lyrics, 600, 30).splitlines()[0] == "[Verse 1]"  # the first section stays
