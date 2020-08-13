#!/usr/bin/env python3

from vosk import Model, KaldiRecognizer, SetLogLevel
import sys
import os
from os import listdir
import wave

path_to_PT_dataset = "/Users/josealves/Documents/speech_recognizer/datasets/phoenix/final_dataset_PT/"

onlyfiles = [f for f in listdir(path_to_PT_dataset) if os.path.isfile(os.path.join(path_to_PT_dataset, f))]

SetLogLevel(0)

if not os.path.exists("model"):
    print ("Please download the model from https://github.com/alphacep/vosk-api/blob/master/doc/models.md and unpack as 'model' in the current folder.")
    exit (1)

model = Model("model")
results = open("results_PT.txt", "w+")

for f in onlyfiles:
    print(f)
    if (f!=".DS_Store"):
        wf = wave.open(path_to_PT_dataset + "/" + f, "rb")
        rec = KaldiRecognizer(model, wf.getframerate())
        if wf.getnchannels() != 1 or wf.getsampwidth() != 2 or wf.getcomptype() != "NONE":
            print ("Audio file must be WAV format mono PCM.")
            exit (1)

        while True:
            len_file = wf.getnframes()
            data = wf.readframes(len_file)
            if len(data) == 0:
                break
            if rec.AcceptWaveform(data):
                print(rec.Result())
            else:
                print(rec.PartialResult())

        begin = rec.Result().find("text")
        end = rec.Result().rfind("}")
        result = rec.Result()
        result_to_save = result[begin+8:end]
        results.write(path_to_PT_dataset + f + " : " + result_to_save )
        print(rec.Result())
results.close()