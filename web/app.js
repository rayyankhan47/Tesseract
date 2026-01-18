const promptInput = document.getElementById("promptInput");
const imageInput = document.getElementById("imageInput");
const generateButton = document.getElementById("generateButton");
const resultLink = document.getElementById("resultLink");
const resultMeta = document.getElementById("resultMeta");
const resultError = document.getElementById("resultError");
const copyButton = document.getElementById("copyButton");
const statusPanel = document.getElementById("statusPanel");
const statusDone = document.getElementById("statusDone");
const statusItems = Array.from(document.querySelectorAll(".status-item"));
const bodyEl = document.body;

const baseText = "I want to build";
const phrases = [
  "the Heidelberg castle...",
  "a redstone ALU",
  "a railway system",
];

let phraseIndex = 0;
let charIndex = baseText.length;
let isDeleting = false;

const typeSpeed = 55;
const deleteSpeed = 28;
const holdDelay = 3000;
const pauseDelay = 700;

function setPlaceholder(text) {
  if (promptInput) {
    promptInput.placeholder = text;
  }
}

function tick() {
  const phrase = phrases[phraseIndex];
  const fullText = `${baseText} ${phrase}`;

  if (!isDeleting) {
    charIndex += 1;
    if (charIndex >= fullText.length) {
      setPlaceholder(fullText);
      isDeleting = true;
      setTimeout(tick, holdDelay);
      return;
    }
    setPlaceholder(fullText.substring(0, charIndex));
    setTimeout(tick, typeSpeed);
    return;
  }

  charIndex -= 1;
  if (charIndex <= baseText.length) {
    charIndex = baseText.length;
    setPlaceholder(baseText);
    isDeleting = false;
    phraseIndex = (phraseIndex + 1) % phrases.length;
    setTimeout(tick, pauseDelay);
    return;
  }
  setPlaceholder(fullText.substring(0, charIndex));
  setTimeout(tick, deleteSpeed);
}

setPlaceholder(baseText);
setTimeout(tick, 600);

async function readFilesAsDataUrls(files) {
  if (!files || files.length === 0) {
    return [];
  }
  const readers = Array.from(files).map(
    (file) =>
      new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve({ name: file.name, dataUrl: reader.result });
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(file);
      })
  );
  return Promise.all(readers);
}

let statusTimers = [];
let statusRevealTimer;

function clearStatusTimers() {
  statusTimers.forEach((timer) => clearTimeout(timer));
  statusTimers = [];
  if (statusRevealTimer) {
    clearTimeout(statusRevealTimer);
    statusRevealTimer = null;
  }
}

function resetStatus() {
  clearStatusTimers();
  statusItems.forEach((item, index) => {
    item.classList.remove("done", "active");
    if (index === 0) {
      item.classList.add("active");
    }
  });
  statusDone?.classList.add("hidden");
  resultError?.classList.add("hidden");
  if (resultError) {
    resultError.textContent = "";
  }
  if (resultLink) {
    resultLink.textContent = "";
  }
  if (resultMeta) {
    resultMeta.textContent = "";
  }
  if (copyButton) {
    copyButton.textContent = "Copy";
  }
}

function startStatusSequence() {
  resetStatus();
  const stepDelay = 15000;
  const advance = (fromIndex, toIndex) => {
    if (!statusItems[fromIndex] || !statusItems[toIndex]) {
      return;
    }
    statusItems[fromIndex].classList.remove("active");
    statusItems[fromIndex].classList.add("done");
    statusItems[toIndex].classList.add("active");
  };
  statusTimers.push(setTimeout(() => advance(0, 1), stepDelay));
  statusTimers.push(setTimeout(() => advance(1, 2), stepDelay * 2));
  statusTimers.push(setTimeout(() => advance(2, 3), stepDelay * 3));
}

function showResult({ url, size, error }) {
  if (!resultLink || !resultMeta || !resultError) {
    return;
  }
  resultError.classList.add("hidden");
  resultError.textContent = "";
  if (error) {
    resultError.textContent = error;
    resultError.classList.remove("hidden");
    resultLink.textContent = "";
    resultMeta.textContent = "";
    statusDone?.classList.remove("hidden");
    return;
  }
  resultLink.textContent = url;
  if (size && size.w && size.h && size.l) {
    resultMeta.textContent = `Done! Load up the Tesseract mod, select a ${size.w}x${size.h}x${size.l} area, and use /tesseract paste ${url} to see your build appear before your eyes.`;
  } else {
    resultMeta.textContent = `Done! Load up the Tesseract mod, select an area, and use /tesseract paste ${url} to see your build appear before your eyes.`;
  }
  statusDone?.classList.remove("hidden");
}

async function handleGenerate() {
  if (!promptInput || !generateButton) {
    return;
  }
  const prompt = promptInput.value.trim();
  if (!prompt) {
    showResult({ error: "Please enter a prompt before generating." });
    return;
  }
  generateButton.disabled = true;
  generateButton.textContent = "Generating...";
  bodyEl.classList.add("is-generating");
  bodyEl.classList.remove("show-status");
  statusPanel?.classList.remove("hidden");
  statusRevealTimer = setTimeout(() => {
    bodyEl.classList.add("show-status");
  }, 500);
  startStatusSequence();

  try {
    const images = await readFilesAsDataUrls(imageInput?.files || []);
    const response = await fetch("/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ prompt, images }),
    });
    const payload = await response.json();
    if (!response.ok || payload.error) {
      throw new Error(payload.error || "Generate failed.");
    }
    statusItems.forEach((item) => item.classList.add("done"));
    statusItems.forEach((item) => item.classList.remove("active"));
    bodyEl.classList.add("show-status");
    showResult(payload);
  } catch (error) {
    bodyEl.classList.add("show-status");
    showResult({ error: error.message || "Generate failed." });
    clearStatusTimers();
  } finally {
    generateButton.disabled = false;
    generateButton.textContent = "Generate build";
  }
}

async function handleCopy() {
  if (!resultLink || !resultLink.textContent) {
    return;
  }
  try {
    await navigator.clipboard.writeText(resultLink.textContent);
    copyButton.textContent = "Copied to Clipboard!";
    setTimeout(() => {
      copyButton.textContent = "Copy";
    }, 1500);
  } catch (error) {
    copyButton.textContent = "Failed";
    setTimeout(() => {
      copyButton.textContent = "Copy";
    }, 1500);
  }
}

generateButton?.addEventListener("click", handleGenerate);
copyButton?.addEventListener("click", handleCopy);
