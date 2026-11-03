// Closed beta terms, version 2026-11-beta, in plain words (carry-over from Sprint 2). The profile
// step records acceptance of this version (components/Account.tsx TERMS_VERSION). The full drafts
// are in docs/14-legal; this is the short form people actually read. Both languages need counsel
// sign-off, and the Hindi needs native review, before the beta opens (TODO.md). If the text
// changes in a way that matters, change the version so everyone accepts again.

import type { Locale } from "./messages";

export const TERMS_VERSION = "2026-11-beta";

export type TermsText = {
  updated: string;
  intro: string;
  sections: { heading: string; points: string[] }[];
  contact: string;
};

const en: TermsText = {
  updated: "Version 2026-11-beta · 3 November 2026",
  intro:
    "These are the rules for the BeatMind AI closed beta. They are short on purpose. By ticking the box when you set up your account, you agree to them. The full Terms of Service, Rights and Licence Terms, Acceptable Use Policy and Privacy Notice apply as well, and these beta terms win if they differ.",
  sections: [
    {
      heading: "1. Who can join",
      points: [
        "You must be 18 or older. If you tell us you are younger, we delete your details straight away.",
        "One free account per person. Accounts made to get around limits are closed.",
        "The beta is by invitation and we can end it, or your place in it, at any time.",
      ],
    },
    {
      heading: "2. It is a beta",
      points: [
        "Features can change, break or be removed, and songs may sound wrong, including mispronounced words.",
        "Languages marked experimental have not passed our listening test yet.",
        "Please tell us what goes wrong. We may use your feedback to improve BeatMind.",
      ],
    },
    {
      heading: "3. Every song is AI-generated",
      points: [
        "Every song is made by AI models and is labelled as AI-generated on screen, in the file's tags, in signed content credentials and with an inaudible watermark.",
        "You must not remove these labels or present a song as made without AI where a platform or the law needs you to say so.",
      ],
    },
    {
      heading: "4. Who owns the songs",
      points: [
        "Free plan: BeatMind owns the songs. You may listen to them personally, and you may licence up to 5 songs a month for commercial use, such as monetised videos, with the credit line “Music: made with BeatMind AI”.",
        "With a free licence you must not claim the song in Content ID or similar systems, release it alone on streaming services, or sell it by itself.",
        "Paid plans: songs made while you are on a paid plan are yours, to the extent of the rights BeatMind holds.",
        "Copyright in AI-made music may not exist in some countries. A BeatMind certificate records what we granted you; it is not a certificate of copyright.",
        "We check every take against recordings we know and hold back close matches, but no check is complete. We do not promise that a song is free of other people's rights or that no platform will claim it.",
      ],
    },
    {
      heading: "5. What you must not do",
      points: [
        "Do not ask for songs that name real artists or copy their voice or style, or that use lyrics you do not have the right to use.",
        "No hate against groups of people, no threats, no sexual content involving children, nothing illegal.",
        "Do not try to get around our safety checks, limits or bot checks.",
        "We may block a request, hold back a take, or pause or close an account that breaks these rules. You can ask a person to review any decision; we aim to answer within 72 hours.",
      ],
    },
    {
      heading: "6. Credits",
      points: [
        "One credit makes one take, and each request makes two. Free credits reset at midnight India time.",
        "If a take fails or our safety check holds it back, its credit comes back automatically.",
        "There are no payments during the closed beta.",
      ],
    },
    {
      heading: "7. Your data",
      points: [
        "We keep your email, the month and year you were born, what you type to make songs, and the songs themselves, to run the service, keep it safe and keep the record of how each song was made.",
        "Text our safety check blocks is not stored, only a fingerprint of it.",
        "When you delete a song it leaves your library at once and its files are removed after 30 days. The record that it was AI-generated is kept, as the law requires.",
        "We do not use your songs or text to train AI models unless you agree to it separately.",
      ],
    },
    {
      heading: "8. Our responsibility",
      points: [
        "We run the beta with reasonable skill and care, but we cannot promise it will always be available or that every song will be what you wanted.",
        "Nothing here limits rights you have by law as a consumer.",
      ],
    },
    {
      heading: "9. Changes and law",
      points: [
        "If we change these terms in a way that matters, we will ask you to accept the new version before you make more songs.",
        "These terms are governed by the laws of India.",
      ],
    },
  ],
  contact: "Questions, complaints or review requests: write to the grievance officer named in the Copyright and Takedown Policy, or use Ask a person to review in the app.",
};

const hi: TermsText = {
  updated: "संस्करण 2026-11-beta · 3 नवंबर 2026",
  intro:
    "ये BeatMind AI क्लोज़्ड बीटा के नियम हैं। इन्हें जान-बूझकर छोटा रखा गया है। अकाउंट बनाते समय बॉक्स पर टिक करके आप इन्हें मानते हैं। पूरी सेवा की शर्तें, अधिकार और लाइसेंस की शर्तें, स्वीकार्य उपयोग नीति और प्राइवेसी नोटिस भी लागू हैं; अगर कुछ अलग हो, तो ये बीटा शर्तें मान्य होंगी।",
  sections: [
    {
      heading: "1. कौन जुड़ सकता है",
      points: [
        "आपकी उम्र 18 साल या उससे ज़्यादा होनी चाहिए। अगर आप बताते हैं कि आप छोटे हैं, तो हम आपकी जानकारी तुरंत मिटा देते हैं।",
        "एक व्यक्ति, एक फ़्री अकाउंट। सीमाओं से बचने के लिए बनाए गए अकाउंट बंद कर दिए जाते हैं।",
        "बीटा सिर्फ़ न्योते से है, और हम इसे या इसमें आपकी जगह कभी भी ख़त्म कर सकते हैं।",
      ],
    },
    {
      heading: "2. यह बीटा है",
      points: [
        "फ़ीचर बदल सकते हैं, बिगड़ सकते हैं या हट सकते हैं, और गाने ग़लत सुनाई दे सकते हैं, जैसे शब्दों का ग़लत उच्चारण।",
        "जिन भाषाओं पर प्रायोगिक लिखा है, वे अभी हमारी सुनने की जाँच में पास नहीं हुई हैं।",
        "जो ग़लत हो, हमें बताइए। आपकी राय से हम BeatMind को बेहतर बना सकते हैं।",
      ],
    },
    {
      heading: "3. हर गाना AI से बना है",
      points: [
        "हर गाना AI मॉडल से बनता है, और उस पर स्क्रीन पर, फ़ाइल के टैग में, साइन किए गए कंटेंट क्रेडेंशियल में और न सुनाई देने वाले वॉटरमार्क से “AI से बना” लेबल होता है।",
        "आप ये लेबल नहीं हटा सकते, और जहाँ कोई प्लेटफ़ॉर्म या क़ानून बताने को कहे, वहाँ गाने को बिना AI के बना हुआ नहीं बता सकते।",
      ],
    },
    {
      heading: "4. गाने किसके हैं",
      points: [
        "फ़्री प्लान: गाने BeatMind के हैं। आप उन्हें निजी तौर पर सुन सकते हैं, और हर महीने 5 गानों तक को कमर्शियल इस्तेमाल के लिए लाइसेंस कर सकते हैं, जैसे कमाई वाले वीडियो में, इस क्रेडिट लाइन के साथ: “Music: made with BeatMind AI”।",
        "फ़्री लाइसेंस में आप गाने को Content ID जैसे सिस्टम में अपना नहीं बता सकते, उसे अकेले स्ट्रीमिंग सेवाओं पर रिलीज़ नहीं कर सकते, और उसे अकेले बेच नहीं सकते।",
        "पेड प्लान: पेड प्लान पर रहते हुए बनाए गए गाने आपके हैं, उतने अधिकारों तक जितने BeatMind के पास हैं।",
        "कुछ देशों में AI से बने संगीत पर कॉपीराइट न भी हो। BeatMind का सर्टिफ़िकेट बताता है कि हमने आपको क्या दिया; यह कॉपीराइट का सर्टिफ़िकेट नहीं है।",
        "हम हर टेक को जानी-पहचानी रिकॉर्डिंग से मिलाते हैं और बहुत मिलते-जुलते टेक रोक लेते हैं, पर कोई भी जाँच पूरी नहीं होती। हम यह वादा नहीं करते कि किसी गाने पर किसी और का अधिकार नहीं है या कोई प्लेटफ़ॉर्म उस पर दावा नहीं करेगा।",
      ],
    },
    {
      heading: "5. क्या नहीं करना है",
      points: [
        "ऐसे गाने न माँगें जिनमें असली कलाकारों का नाम हो या उनकी आवाज़ या स्टाइल की नक़ल हो, या ऐसे बोल जिन्हें इस्तेमाल करने का अधिकार आपके पास नहीं है।",
        "लोगों के समूहों के ख़िलाफ़ नफ़रत नहीं, धमकी नहीं, बच्चों से जुड़ा कोई यौन कंटेंट नहीं, कुछ भी ग़ैरक़ानूनी नहीं।",
        "हमारी सुरक्षा जाँच, सीमाओं या बॉट जाँच से बचने की कोशिश न करें।",
        "इन नियमों को तोड़ने पर हम अनुरोध रोक सकते हैं, टेक रोक सकते हैं, या अकाउंट रोक या बंद कर सकते हैं। आप किसी भी फ़ैसले की जाँच किसी व्यक्ति से करवा सकते हैं; हम 72 घंटों में जवाब देने की कोशिश करते हैं।",
      ],
    },
    {
      heading: "6. क्रेडिट",
      points: [
        "एक क्रेडिट से एक टेक बनता है, और हर अनुरोध दो टेक बनाता है। फ़्री क्रेडिट भारतीय समय से आधी रात को फिर मिलते हैं।",
        "अगर कोई टेक नहीं बन पाता या सुरक्षा जाँच उसे रोक लेती है, तो उसका क्रेडिट अपने आप वापस आ जाता है।",
        "क्लोज़्ड बीटा में कोई भुगतान नहीं है।",
      ],
    },
    {
      heading: "7. आपका डेटा",
      points: [
        "सेवा चलाने, उसे सुरक्षित रखने और हर गाने के बनने का रिकॉर्ड रखने के लिए हम आपका ईमेल, आपके जन्म का महीना और साल, गाना बनाने के लिए आपने जो लिखा, और गाने रखते हैं।",
        "जो टेक्स्ट हमारी सुरक्षा जाँच रोकती है, वह सेव नहीं होता, सिर्फ़ उसका फ़िंगरप्रिंट रहता है।",
        "गाना मिटाने पर वह तुरंत आपकी लाइब्रेरी से हट जाता है और उसकी फ़ाइलें 30 दिनों के बाद हटा दी जाती हैं। गाना AI से बना था, यह रिकॉर्ड क़ानून के मुताबिक़ रखा जाता है।",
        "जब तक आप अलग से हाँ न कहें, हम आपके गानों या टेक्स्ट से AI मॉडल को ट्रेन नहीं करते।",
      ],
    },
    {
      heading: "8. हमारी ज़िम्मेदारी",
      points: [
        "हम बीटा को उचित कौशल और सावधानी से चलाते हैं, पर यह वादा नहीं कर सकते कि यह हमेशा उपलब्ध रहेगा या हर गाना वैसा ही होगा जैसा आप चाहते थे।",
        "यहाँ लिखी कोई बात उपभोक्ता के तौर पर क़ानून से मिले आपके अधिकारों को कम नहीं करती।",
      ],
    },
    {
      heading: "9. बदलाव और क़ानून",
      points: [
        "अगर हम इन शर्तों में कोई अहम बदलाव करते हैं, तो और गाने बनाने से पहले हम आपसे नया संस्करण मानने को कहेंगे।",
        "ये शर्तें भारत के क़ानूनों के तहत हैं।",
      ],
    },
  ],
  contact: "सवाल, शिकायत या जाँच के अनुरोध के लिए: कॉपीराइट और टेकडाउन नीति में दिए शिकायत अधिकारी को लिखें, या ऐप में “किसी व्यक्ति से जाँच करवाएँ” का इस्तेमाल करें।",
};

export function termsText(locale: Locale): TermsText {
  return locale === "hi" ? hi : en;
}

export const TERMS = { en, hi };
