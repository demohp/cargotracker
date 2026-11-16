/**
 * Utility: Returns a Promise that resolves after the given delay (ms).
 * Replaces nested setTimeout callbacks with async/await-compatible delay.
 */
function delay(ms) {
  return new Promise(function (resolve) {
    setTimeout(resolve, ms);
  });
}

/**
 * Initialises the top-progress bar and fades in the main container.
 * Refactored from callback-hell (nested setTimeout) to async/await.
 */
async function initProgress() {
  function resetToDefaults() {
    topbar.config({
      autoRun: true,
      barThickness: 3,
      barColors: {
        '.75': 'rgba(255, 255, 60, .9)',
        '.75': 'rgba(255, 212, 0, .9)',
        '1.0': 'rgba(211, 84,  0, .9)'
      },
      shadowBlur: 10,
      shadowColor: 'rgba(0,   0,   0,   .6)',
      className: 'topbar'
    });
  }

  resetToDefaults();
  topbar.show();

  // Await the delay instead of nesting a callback inside setTimeout
  await delay(1500);
  $('#container').fadeIn('slow');
  topbar.hide();
}

$(function () {
  initProgress().catch(function (err) {
    console.error('Progress initialisation failed:', err);
  });
});
