import sys, json
from faster_whisper import WhisperModel

audio_path = sys.argv[1]
model_size = sys.argv[2] if len(sys.argv) > 2 else "large-v3-turbo"
language   = sys.argv[3] if len(sys.argv) > 3 else None
language   = language if language and language != "auto" else None

# Optional space-separated domain vocabulary (e.g. "API REST GraphQL sprint
# backlog"), passed from WhisperService via whisper.vocabulary-hint in
# application.yml. Combined with the native-script prompt below so the
# decoder both (a) leans toward the correct script and (b) recognizes
# known English/technical loanwords instead of trying to phonetically
# transliterate them into the transcript's main language.
vocabulary_hint = sys.argv[4].strip() if len(sys.argv) > 4 else ""

# Short prompts written in each language's *native script*. Whisper decodes
# toward whichever script its internal token probabilities favor when
# uncertain — for lower-resource languages (Tamil, Malayalam, etc.) that can
# mean it defaults to a phonetic Devanagari-shaped guess instead of the
# actual native script, even when it correctly identified the language.
# Priming the decoder with a few native-script tokens up front fixes this
# in most cases. Extend this map as you hit the same issue in other
# languages — harmless (and skipped entirely) for any language not listed.
NATIVE_SCRIPT_PROMPTS = {
    "ta": "வணக்கம், இது ஒரு கூட்டத்தின் பதிவு.",
    "hi": "नमस्ते, यह एक बैठक की रिकॉर्डिंग है।",
    "te": "నమస్కారం, ఇది ఒక సమావేశం యొక్క రికార్డింగ్.",
    "kn": "ನಮಸ್ಕಾರ, ಇದು ಒಂದು ಸಭೆಯ ರೆಕಾರ್ಡಿಂಗ್.",
    "ml": "നമസ്കാരം, ഇത് ഒരു മീറ്റിംഗിന്റെ റെക്കോർഡിംഗാണ്.",
    "mr": "नमस्कार, ही एका बैठकीची रेकॉर्डिंग आहे.",
    "bn": "নমস্কার, এটি একটি মিটিংয়ের রেকর্ডিং।",
    "gu": "નમસ્તે, આ એક મીટિંગનું રેકોર્ડિંગ છે.",
    "pa": "ਸਤ ਸ੍ਰੀ ਅਕਾਲ, ਇਹ ਇੱਕ ਮੀਟਿੰਗ ਦੀ ਰਿਕਾਰਡਿੰਗ ਹੈ।",
    "ur": "السلام علیکم، یہ ایک میٹنگ کی ریکارڈنگ ہے۔",
}

base_prompt = NATIVE_SCRIPT_PROMPTS.get(language, "") if language else ""
initial_prompt = " ".join(p for p in (base_prompt, vocabulary_hint) if p).strip() or None

model = WhisperModel(model_size, device="cpu", compute_type="int8")
segments, info = model.transcribe(
    audio_path,
    language=language,
    vad_filter=True,
    initial_prompt=initial_prompt,
    # Prevents one mis-decoded segment from poisoning every segment after
    # it — without this (the faster-whisper default is True), a single
    # early stumble on hard/low-resource-language audio can snowball into
    # an increasingly garbled transcript for the rest of the file.
    condition_on_previous_text=False,
)

text = " ".join(seg.text for seg in segments).strip()
print(json.dumps({"text": text, "language": info.language}, ensure_ascii=False))