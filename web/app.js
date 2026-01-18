const promptInput = document.getElementById("promptInput");

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
