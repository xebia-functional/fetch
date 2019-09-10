// This initialization requires that this script is loaded with `defer`
const navElement = document.querySelector("#navigation");

/**
 * Toggle an specific class to the received DOM element.
 * @param {string}	elemSelector The query selector specifying the target element.
 * @param {string}	[activeClass='active'] The class to be applied/removed.
 */
function toggleClass(elemSelector, activeClass = "active") {
  const elem = document.querySelector(elemSelector);
  if (elem) {
    elem.classList.toggle(activeClass);
  }
}

const scrollTop = Math.max(window.pageYOffset, document.documentElement.scrollTop, document.body.scrollTop)

// Navigation element modification through scrolling
function scrollFunction() {
  if (window.pageYOffset || document.documentElement.scrollTop > scrollTop) {
    navElement.classList.add("nav-scroll");
  } else {
    navElement.classList.remove("nav-scroll");
  }
}

// Init call
function loadEvent() {
  document.addEventListener("scroll", scrollFunction);

  const lottieAnimation = bodymovin.loadAnimation({
          container: document.getElementById('fetch-animation'),
          renderer: 'svg' / 'canvas' / 'html',
          loop: true,
          autoplay:true,
          path: 'js/fetch_animation.json'
  })

  document.getElementById('fetch-animation').addEventListener('load', function() {
    lottieAnimation.play();
  });
}

// Attach the functions to each event they are interested in
window.addEventListener("load", loadEvent);
