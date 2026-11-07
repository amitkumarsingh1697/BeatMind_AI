from beatmind_workers import intent

PROMPT = "Hindi romantic song for the wedding first night, Bhojpuri music vibe with orchestra, duet"
ROMANTIC = "[Verse 1]\nतेरी आँखों में सपने हैं\nदिल की धड़कन तेरे नाम\n[Chorus]\nसाजन मेरे पास आ\nसात जनम का वादा"
ROMANTIC_LATIN = "[Verse 1]\nteri aankhon mein sapne hain\ndil ki dhadkan tere naam\nsaajan mere paas aa\nsaat janam ka vaada"


def test_a_plan_that_fits_has_no_problems():
    assert intent.problems(ROMANTIC, "romantic bhojpuri duet", PROMPT, "hi", []) == []
    assert intent.problems(ROMANTIC_LATIN, "romantic duet", PROMPT, "hi", []) == []


def test_empty_and_instrumental_plans_are_rejected():
    assert intent.problems("", "", PROMPT, "hi", []) == ["the plan has no lyrics to sing"]
    assert intent.problems("[Instrumental]", "", PROMPT, "hi", []) == ["the plan has no lyrics to sing"]


def test_lyrics_in_the_wrong_script_are_rejected():
    chinese = intent.problems("[Verse]\n你好 世界\n我爱你\n你好\n世界", "", PROMPT, "hi", [])
    assert chinese[0].startswith("lyrics are not in Hindi")
    assert intent.problems(ROMANTIC, "", "summer pop", "en", [])[0].startswith("lyrics are not in Latin")


def test_a_devotional_plan_for_a_romantic_request_is_rejected():
    found = intent.problems(
        "[Verse]\nजय शिव शंकर\nहरि का नाम\nभोले बाबा\nसबका दाता", "devotional bhajan", PROMPT, "hi", []
    )
    assert any("made it devotional" in p for p in found)
    assert any("lyrics are devotional" in p for p in found)


def test_one_everyday_name_is_not_devotional():
    lyrics = "[Verse]\nram ki shaadi mein\ndil nache\ndhol baje\nsaari raat"
    assert intent.problems(lyrics, "wedding pop", PROMPT, "hinglish", []) == []


def test_devotional_requests_may_be_devotional():
    lyrics = "[Verse]\nजय शिव शंकर\nहरि का नाम\nभोले बाबा\nसबका दाता"
    assert intent.problems(lyrics, "devotional bhajan", "Shiv bhajan", "hi", []) == []


def test_a_second_plan_that_repeats_the_first_is_rejected():
    assert intent.problems(ROMANTIC, "", PROMPT, "hi", [ROMANTIC])[0].startswith("the lyrics repeat take 1")
    other = "[Verse 1]\nचाँदनी रात सुहानी है\nमहकी हवा चले\nबाहों में भर ले\nधीरे धीरे पास आ"
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


def test_an_empty_intro_does_not_use_up_the_length():
    # Seen on the Mac: "[Intro]" kept alone and the verse cut, so the take had nothing to sing.
    lyrics = "\n".join(["[Intro]", "[Verse 1]"] + [f"line {i}" for i in range(8)] + ["[Chorus]", "x", "y"])
    fitted = intent.fit(lyrics, 213, 60)
    assert fitted.splitlines()[:3] == ["[Intro]", "[Verse 1]", "line 0"]
    assert intent.problems(fitted, "", "summer pop", "en", []) == []


def test_a_plan_with_almost_nothing_to_sing_is_rejected():
    assert intent.problems("[Verse]\nla la\nna na", "", "summer pop", "en", []) == [
        "the plan has only 2 lines to sing"
    ]


def test_ritual_words_count_as_devotional():
    # Seen on the Mac for the wedding request: "Ram aaye", "darshan karke".
    lyrics = "[Verse]\nBachpan se aayi Ram aaye\ndarshan karke\nchalo chalein\nshubhkaamna hai"
    assert any("devotional" in p for p in intent.problems(lyrics, "", PROMPT, "hi", []))
