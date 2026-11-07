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
