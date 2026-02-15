import { useState, useRef, useEffect } from 'react';
import { useWebSocket } from 'react-use-websocket/dist/lib/use-websocket';
import { ReadyState } from 'react-use-websocket';
import './App.css';

const WS_URL = 'ws://localhost:8887';

function App() {
  const [transcript, setTranscript] = useState([]);
  const [partial, setPartial] = useState('');
  const [isRecording, setIsRecording] = useState(false);

  // Config State
  const [selectedModel, setSelectedModel] = useState('en-us-small');
  const [mode, setMode] = useState('free'); // 'free', 'grammar', 'auto'
  const [grammarPhrase, setGrammarPhrase] = useState('hello i eat apple and banana'); // Used for both grammar and auto input
  const [targetSyllables, setTargetSyllables] = useState([]); // from 'auto' mode config_update

  const [serverStatus, setServerStatus] = useState('');

  const audioContextRef = useRef(null);
  const streamRef = useRef(null);
  const processorRef = useRef(null);

  const { sendMessage, lastMessage, readyState } = useWebSocket(WS_URL, {
    shouldReconnect: () => true,
    onOpen: () => {
      console.log('WebSocket connected');
    },
    onClose: () => console.log('WebSocket disconnected'),
    onError: (e) => console.error('WebSocket error:', e),
  });

  // Helper to send full config
  const sendConfig = (model, currentMode, grammar) => {
    const msg = {
      type: 'config',
      model: model,
      mode: currentMode,
      grammar: grammar
    };
    sendMessage(JSON.stringify(msg));
    setServerStatus(`Configuring: ${currentMode} mode...`);
  };

  useEffect(() => {
    if (lastMessage !== null) {
      const data = JSON.parse(lastMessage.data);
      if (data.type === 'partial') {
        setPartial(data.text);
      } else if (data.type === 'result') {
        setTranscript((prev) => [...prev, { text: data.text, phonemes: data.phonemes }]);
        setPartial('');
      } else if (data.type === 'status') {
        console.log('Server status:', data.text);
        setServerStatus(data.text);
      } else if (data.type === 'error') {
        console.error('Server error:', data.text);
        setServerStatus(`Error: ${data.text}`);
      } else if (data.type === 'config_update') {
        // Received computed syllables from potential "auto" mode
        console.log('Config Update:', data);
        if (data.syllables) {
          setTargetSyllables(data.syllables);
        }
      }
    }
  }, [lastMessage]);

  const handleModelChange = (e) => {
    const newModel = e.target.value;
    setSelectedModel(newModel);
    if (readyState === ReadyState.OPEN) {
      sendConfig(newModel, mode, grammarPhrase);
    }
  };

  const handleModeChange = (newMode) => {
    setMode(newMode);
    if (newMode === 'free') {
      setTargetSyllables([]);
    }
    if (readyState === ReadyState.OPEN) {
      sendConfig(selectedModel, newMode, grammarPhrase);
    }
  };

  const handleGrammarBlur = () => {
    if ((mode === 'grammar' || mode === 'auto') && readyState === ReadyState.OPEN) {
      sendConfig(selectedModel, mode, grammarPhrase);
    }
  };

  // Calculate which target syllables are matched based on partial/final transcript
  const getMatchedIndex = () => {
    // Combine strict result + partial
    // In Grammar/Auto mode, result IS the tokens.
    const allTokens = [
      ...transcript.map(t => t.text.split(/\s+/)).flat(),
      ...(partial ? partial.split(/\s+/) : [])
    ].flat().filter(x => x && x.trim().length > 0);

    // We assume strict sequential matching for highlighting
    return allTokens.length - 1;
  };

  const matchedIdx = getMatchedIndex();

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;

      const context = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
      audioContextRef.current = context;

      const source = context.createMediaStreamSource(stream);

      const processor = context.createScriptProcessor(4096, 1, 1);
      processorRef.current = processor;

      processor.onaudioprocess = (e) => {
        if (readyState !== ReadyState.OPEN) return;

        const inputData = e.inputBuffer.getChannelData(0);
        const pcmData = new Int16Array(inputData.length);
        for (let i = 0; i < inputData.length; i++) {
          let s = Math.max(-1, Math.min(1, inputData[i]));
          pcmData[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
        }

        sendMessage(pcmData.buffer);
      };

      source.connect(processor);
      processor.connect(context.destination);

      setIsRecording(true);
      // Clear transcript on new recording? Optional.
      setTranscript([]);
      setPartial('');
    } catch (err) {
      console.error('Error starting recording:', err);
    }
  };

  const stopRecording = () => {
    if (processorRef.current) {
      processorRef.current.disconnect();
      processorRef.current.onaudioprocess = null;
    }
    if (audioContextRef.current) {
      audioContextRef.current.close();
    }
    if (streamRef.current) {
      streamRef.current.getTracks().forEach(track => track.stop());
    }
    setIsRecording(false);
  };

  const connectionStatus = {
    [ReadyState.CONNECTING]: 'Connecting',
    [ReadyState.OPEN]: 'Open',
    [ReadyState.CLOSING]: 'Closing',
    [ReadyState.CLOSED]: 'Closed',
    [ReadyState.UNINSTANTIATED]: 'Uninstantiated',
  }[readyState];

  return (
    <div className="App">
      <h1>Phoneme Real-Time POC</h1>

      <div className="controls-container">
        <div className="status-bar">
          <span className={`status-indicator ${connectionStatus.toLowerCase()}`}>●</span> {connectionStatus}
          {serverStatus && <span className="server-msg"> | {serverStatus}</span>}
        </div>

        <div className="config-panel">
          <div className="config-group">
            <label>Model</label>
            <select value={selectedModel} onChange={handleModelChange} disabled={readyState !== ReadyState.OPEN}>
              <option value="en-us-small">US English (Small)</option>
              <option value="en-us-large">US English (Large)</option>
              <option value="en-in">Indian English</option>
            </select>
          </div>

          <div className="config-group">
            <label>Mode</label>
            <div className="toggle-group">
              <button
                className={mode === 'free' ? 'active' : ''}
                onClick={() => handleModeChange('free')}
                disabled={readyState !== ReadyState.OPEN}
              >Free Speech</button>
              <button
                className={mode === 'grammar' ? 'active' : ''}
                onClick={() => handleModeChange('grammar')}
                disabled={readyState !== ReadyState.OPEN}
              >Strict Grammar</button>
              <button
                className={mode === 'auto' ? 'active' : ''}
                onClick={() => handleModeChange('auto')}
                disabled={readyState !== ReadyState.OPEN}
              >Auto Syllables</button>
            </div>
          </div>

          {(mode === 'grammar') && (
            <div className="config-group full-width">
              <label>Target Grammar (Phrase)</label>
              <input
                type="text"
                value={grammarPhrase}
                onChange={(e) => setGrammarPhrase(e.target.value)}
                onBlur={handleGrammarBlur}
                placeholder="e.g. ka ma la"
                disabled={readyState !== ReadyState.OPEN}
              />
              <small>Type phrase and click outside to apply.</small>
            </div>
          )}
          {(mode === 'auto') && (
            <div className="config-group full-width">
              <label>Text Input (Auto-Syllabify)</label>
              <input
                type="text"
                value={grammarPhrase}
                onChange={(e) => setGrammarPhrase(e.target.value)}
                onBlur={handleGrammarBlur}
                placeholder="e.g. Hello Kamala"
                disabled={readyState !== ReadyState.OPEN}
              />
              <small>Type natural text. System will compute strict phoneme grammar.</small>
            </div>
          )}
        </div>

        {/* Visual Target for Auto Mode */}
        {mode === 'auto' && targetSyllables.length > 0 && (
          <div className="target-display">
            <h3>Target Sequence:</h3>
            <div className="syllable-track">
              {targetSyllables.map((syl, i) => (
                <div key={i} className={`syllable-block ${i <= matchedIdx ? 'matched' : ''}`}>
                  {syl.display || syl}
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="action-buttons">
          {!isRecording ? (
            <button onClick={startRecording} disabled={readyState !== ReadyState.OPEN} className="record-btn start">
              Start Microphone
            </button>
          ) : (
            <button onClick={stopRecording} className="record-btn stop">
              Stop Microphone
            </button>
          )}
        </div>
      </div>

      <div className="transcription-box">
        {mode === 'free' && transcript.map((item, idx) => (
          <div key={idx} className={`result-item ${mode}`}>
            <span className="word">{item.text}</span>
            <span className="phonemes">[{item.phonemes}]</span>
          </div>
        ))}
        {/* In grammar/auto mode, we might just rely on the Target Display, but let's show raw output too */}
        {(mode !== 'free') && transcript.map((item, idx) => (
          <span key={idx} className="result-token">{item.text} </span>
        ))}
        {partial && (
          <div className="partial-item">
            <span className="word partial">{partial}</span>
          </div>
        )}
      </div>
    </div>
  );
}

export default App;
