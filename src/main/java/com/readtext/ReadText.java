package com.readtext;

import com.readtext.ReadText.GCSEvent;
import com.google.cloud.functions.BackgroundFunction;
import com.google.cloud.functions.Context;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.texttospeech.v1.AudioConfig;
import com.google.cloud.texttospeech.v1.AudioEncoding;
import com.google.cloud.texttospeech.v1.SynthesisInput;
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse;
import com.google.cloud.texttospeech.v1.TextToSpeechClient;
import com.google.cloud.texttospeech.v1.VoiceSelectionParams;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.SequenceInputStream;
import java.util.Collections;
import java.util.logging.Logger;

public class ReadText implements BackgroundFunction<GCSEvent> {
  private static final Logger logger = Logger.getLogger(ReadText.class.getName());
  private static final Storage STORAGE = StorageOptions.getDefaultInstance().getService();
  private static final String INPUT_PREFIX = ".speechify_me";
  private static final String OUTPUT_PREFIX = ".wav";
  private static final AudioEncoding AUDIO_ENCODING = AudioEncoding.LINEAR16;
  private static final int CHARACTER_LIMIT = 4999;
  private static final int FIND_BREAK_AFTER = 4000;
  private static final ImmutableList<String> PRIORITIZED_TEXT_STOPS =
          ImmutableList.of("\n\n", "\n", ". ", ".", ",", " ");

  private static final SpeechConfig EN_US_CONFIG =
          new SpeechConfig("en-US", "en-US-Wavenet-D", -4.4, 1.2);
  private static final SpeechConfig ES_US_CONFIG =
          new SpeechConfig("es-US", "es-US-Wavenet-B", -5.0, 1.0);

  @Override
  public void accept(GCSEvent event, Context context) throws Exception {
    String bucket = event.getBucket();
    String filename = event.getName();
    String gcsPath = String.format("gs://%s/%s", bucket, filename);
    logger.info("Processing file: " + gcsPath);
    if (!filename.endsWith(INPUT_PREFIX)) {
      logger.info("Early exit for file '" + gcsPath + "' with unexpected file type.");
      return;
    }

    Blob blob = STORAGE.get(bucket, filename);
    String fileContent = new String(blob.getContent());
    logger.info("Read data: " + fileContent);

    ImmutableList<ByteString> speechChunks = speechify(fileContent, extractLanguageCodeFromFileName(filename));

    ImmutableList.Builder<AudioInputStream> audioStreamsBuilder = ImmutableList.builder();
    int totalStreamLength = 0;
    for (int i = 0; i < speechChunks.size(); i++) {
      logger.info(String.format("reading chunk: " + i));
      ByteString speechChunk = speechChunks.get(i);
      AudioInputStream inputStream = AudioSystem.getAudioInputStream(speechChunk.newInput());
      audioStreamsBuilder.add(inputStream);
      totalStreamLength += inputStream.getFrameLength();
      logger.info(String.format("total stream length is now: " + totalStreamLength));
    }
    ImmutableList<AudioInputStream> audioStreams = audioStreamsBuilder.build();
    SequenceInputStream sequenceInputStream = new SequenceInputStream(Collections.enumeration(audioStreams));
    AudioInputStream outputStream = new AudioInputStream(
            sequenceInputStream,
            audioStreams.get(0).getFormat(),
            totalStreamLength);
    String outputFilename = filename.replace(INPUT_PREFIX, OUTPUT_PREFIX);
    logger.info(String.format("Saving result to %s in bucket %s", outputFilename, bucket));
    BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, outputFilename)).setContentType("audio/wav").build();
    STORAGE.create(blobInfo, outputStream.readAllBytes());
    logger.info("File " + outputFilename + " saved");
  }

  private SpeechConfig extractLanguageCodeFromFileName(String filename) {
    if (filename.contains("." + ES_US_CONFIG.getLanguageCode() + ".")) {
      return ES_US_CONFIG;
    }
    return EN_US_CONFIG;
  }

  private ImmutableList<ByteString> speechify(String text, SpeechConfig speechConfig) throws Exception {
    logger.info("Speechifying starting");
    // Break down the string into 5K character limits for speechifying API.
    ImmutableList<String> processableChunks = breakIntoProcessableChunks(text);
    // Instantiates a client
    try (TextToSpeechClient textToSpeechClient = TextToSpeechClient.create()) {
      // Build the voice request, select the language code ("en-US") and the ssml voice gender
      // ("neutral")
      VoiceSelectionParams voice =
              VoiceSelectionParams.newBuilder()
                      .setLanguageCode(speechConfig.getLanguageCode())
                      .setName(speechConfig.getVoiceName())
                      .build();

      // Select the type of audio file you want returned
      AudioConfig audioConfig =
              AudioConfig.newBuilder()
                      .setAudioEncoding(AUDIO_ENCODING)
                      .setPitch(speechConfig.getPitch())
                      .setSpeakingRate(speechConfig.getSpeakingRate())
                      .build();

      int callCount = 0;
      ImmutableList.Builder<ByteString> responseList = ImmutableList.builder();
      for (String chunkToProcess : processableChunks) {
        //  Set the text input to be synthesized
        SynthesisInput input = SynthesisInput.newBuilder().setSsml(chunkToProcess).build();
        // Perform the text-to-speech request on the text input with the selected voice parameters and
        // audio file type
        SynthesizeSpeechResponse response =
                textToSpeechClient.synthesizeSpeech(input, voice, audioConfig);
        logger.info("Speechifying call " + (++callCount) + " complete.");
        responseList.add(response.getAudioContent());
      }

      logger.info("Speechifying complete.");
      // Get the audio contents from the response
      return responseList.build();
    }
  }

  private ImmutableList<String> breakIntoProcessableChunks(String text) {
    ImmutableList.Builder<String> responseChunks = ImmutableList.builder();
    String currentText = text;
    while (currentText.length() > 0) {
      int idealBreakIndex = getIdealBreakIndex(currentText);
      logger.info("Ideal break found at: " + idealBreakIndex);
      if (idealBreakIndex < 0) {
        logger.warning("Couldn't find a good break point for the text. Skipping remaining " +
                currentText.length() + " characters.");
        return responseChunks.build();
      }
      responseChunks.add(currentText.substring(0, idealBreakIndex));
      currentText = currentText.substring(idealBreakIndex);
    }
    return responseChunks.build();
  }

  private int getIdealBreakIndex(String text) {
    int characterLimit = getCharacterLimit();
    int findAfterBreak = getFindBreakAfter();

    if (text.length() <= characterLimit) {
      return text.length();
    }

    int idealBreakIndex = -1;
    for (int i = 0;
         i < PRIORITIZED_TEXT_STOPS.size() && (idealBreakIndex < 0 || idealBreakIndex >= CHARACTER_LIMIT);
         i++) {
      idealBreakIndex = text.indexOf(PRIORITIZED_TEXT_STOPS.get(i), findAfterBreak);
    }
    return idealBreakIndex;
  }

  private int getCharacterLimit() {
    String characterLimitString = System.getenv("CHARACTER_LIMIT");
    return characterLimitString == null || characterLimitString.isEmpty() ?
            CHARACTER_LIMIT : Integer.parseInt(System.getenv("CHARACTER_LIMIT"));
  }
  private int getFindBreakAfter() {
    String findBreakAfterString = System.getenv("FIND_BREAK_AFTER");
    return findBreakAfterString == null || findBreakAfterString.isEmpty() ?
            FIND_BREAK_AFTER : Integer.parseInt(System.getenv("FIND_BREAK_AFTER"));
  }

  public static class SpeechConfig {
    private final String languageCode;
    private final String voiceName;
    private final double pitch;
    private final double speakingRate;

    public SpeechConfig(String languageCode, String voiceName, double pitch, double speakingRate) {
      this.languageCode = languageCode;
      this.voiceName = voiceName;
      this.pitch = pitch;
      this.speakingRate = speakingRate;
    }

    public String getLanguageCode() {
      return languageCode;
    }

    public String getVoiceName() {
      return voiceName;
    }

    public double getPitch() {
      return pitch;
    }

    public double getSpeakingRate() {
      return speakingRate;
    }
  }

  @SuppressWarnings("unused")
  public static class GCSEvent {
    // Cloud Functions uses GSON to populate this object.
    // Field types/names are specified by Cloud Functions
    // Changing them may break your code!
    private String bucket;
    private String name;
    private String metageneration;

    public String getBucket() {
      return bucket;
    }

    public void setBucket(String bucket) {
      this.bucket = bucket;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getMetageneration() {
      return metageneration;
    }

    public void setMetageneration(String metageneration) {
      this.metageneration = metageneration;
    }
  }
}
