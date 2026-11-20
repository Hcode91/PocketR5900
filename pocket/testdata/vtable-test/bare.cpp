struct Shape {
    virtual int area() const = 0;
    virtual int sides() const = 0;
};
struct Square : Shape {
    int s; explicit Square(int v) : s(v) {}
    int area() const override { return s * s; }
    int sides() const override { return 4; }
};
struct Tri : Shape {
    int b, h; Tri(int x, int y) : b(x), h(y) {}
    int area() const override { return b * h / 2; }
    int sides() const override { return 3; }
};
volatile int pick = 1;
extern "C" void __attribute__((noinline, noreturn)) result_ok() { for (;;) asm volatile("nop"); }
extern "C" void __attribute__((noinline, noreturn)) result_bad() { for (;;) asm volatile("nop; nop"); }
extern "C" void __attribute__((noreturn)) bare_main() {
    Square sq(5);
    Tri tr(6, 4);
    Shape *volatile shapes[2] = { &sq, &tr };
    int total = 0;
    for (int i = 0; i < 2; ++i) {
        Shape *p = shapes[(i + pick) & 1];
        total += p->area() * 10 + p->sides();
    }
    // 25*10+4 + 12*10+3 = 377
    if (total == 377) result_ok();
    result_bad();
}
