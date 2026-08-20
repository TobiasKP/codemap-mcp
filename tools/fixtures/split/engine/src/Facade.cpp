#include "Facade.hpp"

// Deliberately long, and in a different folder from the header: the class node must
// absorb these lines instead of becoming a second node here.
void Facade::run() {
    helper.doIt();
    helper.doIt();
}
void Facade::alsoHere() {
    helper.doIt();
}
