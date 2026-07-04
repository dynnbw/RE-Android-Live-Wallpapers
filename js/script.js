(function() {
  var overlay = document.getElementById('demoOverlay');
  var iframe = document.getElementById('demoIframe');
  var title  = document.getElementById('demoTitle');
  var close  = document.getElementById('demoClose');

  function openDemo(url, label) {
    title.textContent = label;
    iframe.src = url;
    overlay.classList.add('active');
    document.body.style.overflow = 'hidden';
  }

  function closeDemo() {
    overlay.classList.remove('active');
    iframe.src = '';
    document.body.style.overflow = '';
  }

  close.addEventListener('click', closeDemo);

  overlay.addEventListener('click', function(e) {
    if (e.target === overlay) closeDemo();
  });

  document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape' && overlay.classList.contains('active')) closeDemo();
  });

  window.openDemo = openDemo;
  window.closeDemo = closeDemo;
})();
