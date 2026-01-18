const promptInput = document.getElementById("promptInput");
const imageInput = document.getElementById("imageInput");
const generateButton = document.getElementById("generateButton");
const result = document.getElementById("result");
const resultLink = document.getElementById("resultLink");
const resultStatus = document.getElementById("resultStatus");
const resultMeta = document.getElementById("resultMeta");
const resultError = document.getElementById("resultError");
const copyButton = document.getElementById("copyButton");

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

function showResult({ url, size, error, status }) {
  if (!result || !resultLink || !resultMeta || !resultError || !resultStatus) {
    return;
  }
  result.classList.remove("hidden");
  resultError.classList.add("hidden");
  resultError.textContent = "";
  resultStatus.textContent = status || "";
  if (error) {
    resultError.textContent = error;
    resultError.classList.remove("hidden");
    resultLink.textContent = "";
    resultLink.href = "#";
    resultMeta.textContent = "";
    return;
  }
  resultLink.textContent = url;
  resultLink.href = url;
  if (size && size.w && size.h && size.l) {
    resultMeta.textContent = `Select a ${size.w}x${size.h}x${size.l} region, then /tesseract paste ${url}`;
  } else {
    resultMeta.textContent = `Use /tesseract paste ${url}`;
  }
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
  showResult({ status: "Generating build plan..." });

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
    showResult(payload);
  } catch (error) {
    showResult({ error: error.message || "Generate failed." });
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
    copyButton.textContent = "Copied";
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
