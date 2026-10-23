//  SpektraLab-Bridging-Header.h -- the engine's C ABI, visible to Swift.
//
//  The same one-line boundary as the Mac app's (RFC-014 §2.1): Swift sees the
//  `extern "C"` surface in `spk_engine.h` and nothing else, and the engine's
//  C++ compiles as C++20 in the same target.
#import "spektrafilm/spk_engine.h"
