JAVAC = javac
JAVA = java
SRC = TCPend.java Sender.java Receiver.java Util.java
CLASS = TCPend.class Sender.class Receiver.class Util.class

all: $(CLASS)

$(CLASS): $(SRC)
	$(JAVAC) $(SRC)

clean:
	rm -f *.class
	rm output
	touch output
