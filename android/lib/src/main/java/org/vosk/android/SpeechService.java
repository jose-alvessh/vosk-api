// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.media.audiofx.AcousticEchoCanceler;


import android.os.Handler;
import android.os.Looper;
import android.annotation.SuppressLint;

import org.vosk.Recognizer;
import java.io.IOException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Service that records audio in a thread, passes it to a recognizer and emits
 * recognition results. Recognition events are passed to a client using
 * {@link RecognitionListener}
 */
public class SpeechService {

    private final Recognizer recognizer;

    private final int sampleRate;
    private final static float BUFFER_SIZE_SECONDS = 0.4f;
    private final static int RECOGNIZER_QUEUE = 3;
    private final static int MINIMUM_CHUNKS_FOR_DELAY = 2;

    private final int bufferSize;
    private final AudioRecord recorder;

    private RecognizerThread recognizerThread;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TranscriptionThread transcriptionThread;

    RecognitionListener listener;

    private boolean isToSaveAudios = false;


    /**
     * Creates speech service. Service holds the AudioRecord object, so you
     * need to call {@link #shutdown()} in order to properly finalize it.
     *
     * @throws IOException thrown if audio recorder can not be created for some reason.
     */
    @SuppressLint("MissingPermission")
    public SpeechService(Recognizer recognizer, float sampleRate, boolean isToSaveAudios)
            throws IOException {
        this.recognizer = recognizer;
        this.sampleRate = (int) sampleRate;
        this.isToSaveAudios = isToSaveAudios;

        bufferSize = Math.round(this.sampleRate * BUFFER_SIZE_SECONDS);
        recorder = new AudioRecord(
                AudioSource.VOICE_COMMUNICATION, this.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);

        if (AcousticEchoCanceler.isAvailable()) {
            System.out.println("!! echo canceller is available !!");

            int sessionID = recorder.getAudioSessionId();
            AcousticEchoCanceler acousticEchoCanceler = AcousticEchoCanceler.create(sessionID);
            acousticEchoCanceler.setEnabled(true);

            if (acousticEchoCanceler.getEnabled()) {
                System.out.println("!! echo canceler is enabled !!");
            }
        }



        if (recorder.getState() == AudioRecord.STATE_UNINITIALIZED) {
            recorder.release();
            throw new IOException(
                    "Failed to initialize recorder. Microphone might be already in use.");
        }
    }


    /**
     * Starts recognition. Does nothing if recognition is active.
     *
     * @return true if recognition was actually started
     */
    public boolean startListening(RecognitionListener listener) {
        if (null != recognizerThread)
            return false;

        recognizerThread = new RecognizerThread(listener, this.isToSaveAudios);
        recognizerThread.start();
        return true;
    }

    /**
     * Starts recognition. After specified timeout listening stops and the
     * endOfSpeech signals about that. Does nothing if recognition is active.
     * <p>
     * timeout - timeout in milliseconds to listen.
     *
     * @return true if recognition was actually started
     */
    public boolean startListening(RecognitionListener listener, int timeout, boolean isToSaveAudios) {

        if (null != recognizerThread)
            return false;

        this.listener = listener;

        recognizerThread = new RecognizerThread(listener, timeout, isToSaveAudios);
        recognizerThread.start();

        return true;
    }

    private boolean stopRecognizerThread() {
        if (null == recognizerThread)
            return false;

        try {
            recognizerThread.interrupt();
            recognizerThread.join();
        } catch (InterruptedException e) {
            // Restore the interrupted status.
            Thread.currentThread().interrupt();
        }

        recognizerThread = null;
        return true;
    }

    /**
     * Stops recognition. Listener should receive final result if there is
     * any. Does nothing if recognition is not active.
     *
     * @return true if recognition was actually stopped
     */
    public boolean stop() {
        return stopRecognizerThread();
    }

    /**
     * Cancel recognition. Do not post any new events, simply cancel processing.
     * Does nothing if recognition is not active.
     *
     * @return true if recognition was actually stopped
     */
    public boolean cancel() {
        if (recognizerThread != null) {
            recognizerThread.setPause(true);
        }
        return stopRecognizerThread();
    }

    /**
     * Shutdown the recognizer and release the recorder
     */
    public void shutdown() {
        recorder.release();
    }

    public void setPause(boolean paused) {
        if (recognizerThread != null) {
            recognizerThread.setPause(paused);
        }
    }

    /**
     * Resets recognizer in a thread, starts recognition over again
     */
    public void reset() {
        if (recognizerThread != null) {
            recognizerThread.reset();
        }
    }

    private final class RecognizerThread extends Thread {

        private int remainingSamples;
        private final int timeoutSamples;
        private final static int NO_TIMEOUT = -1;
        private volatile boolean paused = false;
        private volatile boolean reset = false;

        private boolean isToSaveAudios = false;
        RecognitionListener listener;

        public RecognizerThread(RecognitionListener listener, int timeout, boolean isToSaveAudios) {

            this.isToSaveAudios = isToSaveAudios;

            this.listener = listener;
            if (timeout != NO_TIMEOUT)
                this.timeoutSamples = timeout * sampleRate / 1000;
            else
                this.timeoutSamples = NO_TIMEOUT;
            this.remainingSamples = this.timeoutSamples;

            // Opens the trascription thread that will run the transcription of the audios on a
            // different thread. This thread will have a queue of audios that will run in a sync
            // order.
            transcriptionThread = new TranscriptionThread(RECOGNIZER_QUEUE, listener, isToSaveAudios);

            transcriptionThread.start();
        }

        public RecognizerThread(RecognitionListener listener, boolean isToSaveAudios) {
            this(listener, NO_TIMEOUT, isToSaveAudios);
        }

        /**
         * When we are paused, don't process audio by the recognizer and don't emit
         * any listener results
         *
         * @param paused the status of pause
         */
        public void setPause(boolean paused) {
            this.paused = paused;
        }

        /**
         * Set reset state to signal reset of the recognizer and start over
         */
        public void reset() {
            this.reset = true;
        }


        @Override
        public void run() {
            recorder.startRecording();

            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED) {
                recorder.stop();
                IOException ioe = new IOException(
                        "Failed to start recording. Microphone might be already in use.");
                mainHandler.post(() -> listener.onError(ioe));
            }

            short[] buffer = new short[bufferSize];

            while (!interrupted()
                    && ((timeoutSamples == NO_TIMEOUT) || (remainingSamples > 0))) {

                // Reads the data from the audio recorder
                int nread = recorder.read(buffer, 0, buffer.length);

                if (paused) {
                    continue;
                }

                if (reset) {
                    transcriptionThread.resetRecognizer();
                    reset = false;
                }

                if (nread < 0)
                    throw new RuntimeException("error reading audio buffer");

                // Adds the data read from the AudioRecorder to the queue of chunks of audio
                try {
                    // Gets the number of elements of the queue
                    int chunksInQueue = transcriptionThread.recordingChunksQueue.size();

                    //Checks if the queue is full
                    if (chunksInQueue >= RECOGNIZER_QUEUE) {
                        transcriptionThread.resetRecognizer();
                        if (listener != null) {
                            listener.onTranscriptionFailed();
                        }
                    } else if (chunksInQueue >= MINIMUM_CHUNKS_FOR_DELAY) {
                        if (listener != null) {
                            listener.onTransciptionDelayed((float) chunksInQueue * BUFFER_SIZE_SECONDS);
                        }
                    }

                    transcriptionThread.recordingChunksQueue.put(new RecordingChunk(buffer, nread));

                } catch (InterruptedException e) {
                    interrupt();
                }
            }

            recorder.stop();
            listener.onRecognizerStopped();

        }

        @Override
        public void interrupt() {
            transcriptionThread.interrupt();
            super.interrupt();
        }
    }

    /**
     * Class used to save the audio buffer got from AudioRecorder and the respective number of
     * shorts read
     * @param audioBuffer - contains the buffer of audio
     * @param nread - number of shorts read
     *
     */
    private static final class RecordingChunk {
        private final int nread;
        private final short[] audioBuffer;

        RecordingChunk(short[] audioBuffer, int nread) {
            this.audioBuffer = audioBuffer;
            this.nread = nread;
        }
    }

    /**
     * This class extends a thread that will be responsible to run the chunks of audio recorded
     * from the AudioRecorder. It has a BlockingQueue that will make the audios run in the same order
     * in which they were recorder as well as wait for any of them to be finished before running
     * the next one (the recognizer instance depends on the context of the previous chunks of audio
     * and therefore it should transcript the audios sequentially)
     *
     * @param - queueSize - size of the queue that will save the chunks of audio
     * @param - recognitionListener - listener that will get the results from the transcription and
     * transmit them to the UI
     */
    class TranscriptionThread extends Thread {

        private BlockingQueue<RecordingChunk> recordingChunksQueue;

        RecognitionListener recognitionListener;

        private boolean isToResetRecognizer = false;

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        private boolean isToSaveAudios = false;

        TranscriptionThread(int queueSize, RecognitionListener recognitionListener,
                            boolean isToSaveAudios) {
            this.recordingChunksQueue = new ArrayBlockingQueue<>(queueSize);
            this.recognitionListener = recognitionListener;
            this.isToSaveAudios = isToSaveAudios;
        }

        private void resetRecognizer() {
            this.isToResetRecognizer = true;
        }

        @Override
        public void run() {

            while (!isInterrupted()) {
                try {
                    // Takes one chunk of the audio from the saved queue to run it in a model
                    RecordingChunk chunk = recordingChunksQueue.take();

                    if (chunk != null) {
                        if (isToSaveAudios) {
                            byte[] bdata = new byte[chunk.nread * 2];

                            ByteBuffer.wrap(bdata).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().
                                    put(chunk.audioBuffer, 0, chunk.nread);

                            try {
                                outputStream.write(bdata);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                        // Runs the audio chunk on the model to get the transcription
                        boolean accepted = recognizer.acceptWaveForm(chunk.audioBuffer, chunk.nread);

                        if (accepted) {
                            // If accepted is true it means that the sentence has ended which
                            // then means that the model has a result for that specific sentence
                            final String result = recognizer.getResult();

                            if (isToSaveAudios) {
                                byte[] resultBuffer = outputStream.toByteArray();
                                outputStream = new ByteArrayOutputStream();
                                mainHandler.post(() -> recognitionListener.onResult(result, resultBuffer));
                            } else {
                                mainHandler.post(() -> recognitionListener.onResult(result, null));
                            }

                        } else {
                            // If not we get a partialResult that allows to detect if the user is
                            // speaking or not
                            final String partialResult = recognizer.getPartialResult();
                            mainHandler.post(() -> recognitionListener.onPartialResult(partialResult));
                        }
                    }

                    if (isToResetRecognizer) {
                        recordingChunksQueue.clear();
                        isToResetRecognizer = false;
                    }

                } catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
