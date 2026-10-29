// Waitlist copy (US-007). Hindi text needs review by a native speaker before launch.
// Claims here must match what the product will do (PRD v2.1); nothing is promised that is not planned.

export const LOCALES = ["en", "hi"] as const;
export type Locale = (typeof LOCALES)[number];

export const CONSENT_TEXT_VERSION = "2026-10-v1";

export type Messages = {
  langName: string;
  otherLang: { code: Locale; label: string };
  title: string;
  tagline: string;
  points: string[];
  pricesHeading: string;
  creator: string;
  pro: string;
  perMonth: string;
  inclGst: string;
  plusTax: string;
  priceNote: string;
  noPaidPlans: string;
  formHeading: string;
  email: string;
  useCase: string;
  useCases: { value: string; label: string }[];
  consent: string;
  adult: string;
  submit: string;
  sending: string;
  thanks: string;
  error: string;
  privacy: string;
  comingSoon: string;
};

const en: Messages = {
  langName: "English",
  otherLang: { code: "hi", label: "हिन्दी" },
  title: "BeatMind AI",
  tagline: "Original songs in your language, at your price, that you own.",
  points: [
    "Type a vibe or paste your lyrics in Hindi, Hinglish or English.",
    "Get two takes in about a minute, keep the one you like, and download it.",
    "On a paid plan, the songs you make are yours to use anywhere.",
    "Every song is labelled as AI-made and comes with a licence certificate.",
  ],
  pricesHeading: "Planned prices",
  creator: "Creator",
  pro: "Pro",
  perMonth: "/month",
  inclGst: "including GST",
  plusTax: "plus local tax",
  priceNote: "Nothing is charged now. Prices may change before launch.",
  noPaidPlans: "Paid plans are not planned for your country yet. You can still join the waitlist.",
  formHeading: "Join the waitlist",
  email: "Email",
  useCase: "What would you use it for?",
  useCases: [
    { value: "youtube", label: "YouTube videos" },
    { value: "shorts-reels", label: "Shorts and Reels" },
    { value: "podcast", label: "Podcasts" },
    { value: "own-songs", label: "My own songs (artist or lyricist)" },
    { value: "production", label: "Music production and beats" },
    { value: "other", label: "Something else" },
  ],
  consent:
    "Email me when the beta opens, and occasionally with an invitation to a short research interview. I can ask to be removed at any time.",
  adult: "I am 18 or older.",
  submit: "Join the waitlist",
  sending: "Sending…",
  thanks: "Thank you. You're on the list, and we'll email you when the beta opens.",
  error: "Something went wrong. Please check your details and try again.",
  privacy:
    "We store your email, country, language, the use you picked and which prices you were shown, only to run the waitlist and our price research. We don't sell it. Write to us to see or delete your data.",
  comingSoon: "Coming soon. Closed beta planned for early 2027.",
};

const hi: Messages = {
  langName: "हिन्दी",
  otherLang: { code: "en", label: "English" },
  title: "BeatMind AI",
  tagline: "आपकी भाषा में, आपके बजट में, ओरिजिनल गाने जो आपके अपने हों।",
  points: [
    "हिन्दी, हिंग्लिश या अंग्रेज़ी में अपना मूड लिखें या अपने बोल पेस्ट करें।",
    "लगभग एक मिनट में दो वर्ज़न पाएँ, जो पसंद आए उसे रखें और डाउनलोड करें।",
    "पेड प्लान पर, आपके बनाए गाने आपके हैं, जहाँ चाहें इस्तेमाल करें।",
    "हर गाने पर AI से बना होने का लेबल और एक लाइसेंस सर्टिफ़िकेट होता है।",
  ],
  pricesHeading: "प्रस्तावित कीमतें",
  creator: "Creator",
  pro: "Pro",
  perMonth: "/महीना",
  inclGst: "GST सहित",
  plusTax: "स्थानीय टैक्स अलग",
  priceNote: "अभी कोई पैसा नहीं लिया जा रहा। लॉन्च से पहले कीमतें बदल सकती हैं।",
  noPaidPlans: "आपके देश के लिए अभी पेड प्लान की योजना नहीं है। आप फिर भी वेटलिस्ट में शामिल हो सकते हैं।",
  formHeading: "वेटलिस्ट में शामिल हों",
  email: "ईमेल",
  useCase: "आप इसे किस काम के लिए इस्तेमाल करेंगे?",
  useCases: [
    { value: "youtube", label: "YouTube वीडियो" },
    { value: "shorts-reels", label: "Shorts और Reels" },
    { value: "podcast", label: "पॉडकास्ट" },
    { value: "own-songs", label: "अपने गाने (कलाकार या गीतकार)" },
    { value: "production", label: "म्यूज़िक प्रोडक्शन और बीट्स" },
    { value: "other", label: "कुछ और" },
  ],
  consent:
    "बीटा शुरू होने पर मुझे ईमेल करें, और कभी-कभी एक छोटे रिसर्च इंटरव्यू का न्योता भी भेजें। मैं कभी भी हटाने के लिए कह सकता/सकती हूँ।",
  adult: "मेरी उम्र 18 साल या उससे ज़्यादा है।",
  submit: "वेटलिस्ट में शामिल हों",
  sending: "भेजा जा रहा है…",
  thanks: "धन्यवाद। आप लिस्ट में हैं, बीटा शुरू होने पर हम आपको ईमेल करेंगे।",
  error: "कुछ गड़बड़ हो गई। कृपया अपनी जानकारी जाँचें और फिर से कोशिश करें।",
  privacy:
    "हम आपका ईमेल, देश, भाषा, चुना गया इस्तेमाल और आपको दिखाई गई कीमतें सिर्फ़ वेटलिस्ट और कीमत से जुड़ी रिसर्च के लिए रखते हैं। हम इसे बेचते नहीं। अपना डेटा देखने या हटाने के लिए हमें लिखें।",
  comingSoon: "जल्द आ रहा है। क्लोज़्ड बीटा 2027 की शुरुआत में।",
};

export const messages: Record<Locale, Messages> = { en, hi };

export function isLocale(value: string): value is Locale {
  return (LOCALES as readonly string[]).includes(value);
}
