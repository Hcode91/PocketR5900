typedef void (*fn)(void);
extern fn __init_array_start[] __attribute__((weak));
extern fn __init_array_end[] __attribute__((weak));
extern "C" void ctors(void) { for (fn *f = __init_array_start; f && f < __init_array_end; ++f) (*f)(); }
