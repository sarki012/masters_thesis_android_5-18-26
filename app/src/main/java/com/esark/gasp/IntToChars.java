package com.esark.gasp;

public class IntToChars {
    String[] tripleString = new String[] {"0", "0", "0", "0"};
    public IntToChars(){
    }
    public String[] IntToCharsMethod(int integer){
        //int to char code here...
        if(integer < 0){
            tripleString[3] = "-";
        }
        else{
            tripleString[3] = "+";
        }
        integer = Math.abs(integer);
        //Take the remainder of divide by 10
        switch((int)integer%10) {
            case 0 :
                tripleString[0] = "0";
                break;
            case 1 :
                tripleString[0] = "1";
                break;
            case 2 :
                tripleString[0] = "2";
                break;
            case 3 :
                tripleString[0] = "3";
                break;
            case 4 :
                tripleString[0] = "4";
                break;
            case 5 :
                tripleString[0] = "5";
                break;
            case 6 :
                tripleString[0] = "6";
                break;
            case 7 :
                tripleString[0] = "7";
                break;
            case 8 :
                tripleString[0] = "8";
                break;
            case 9 :
                tripleString[0] = "9";
                break;
            default :
                tripleString[0] = "-";
        }
        integer /= 10;      //Get rid of the right digit
        switch((int)integer%10) {       //Take the remainder of divide by 10
            case 0 :
                tripleString[1] = "0";
                break;
            case 1 :
                tripleString[1] = "1";
                break;
            case 2 :
                tripleString[1] = "2";
                break;
            case 3 :
                tripleString[1] = "3";
                break;
            case 4 :
                tripleString[1] = "4";
                break;
            case 5 :
                tripleString[1] = "5";
                break;
            case 6 :
                tripleString[1] = "6";
                break;
            case 7 :
                tripleString[1] = "7";
                break;
            case 8 :
                tripleString[1] = "8";
                break;
            case 9 :
                tripleString[1] = "9";
                break;
            default :
                tripleString[1] = "-";
        }
        integer /= 10;      //Get rid of the right digit
        switch((int)integer) {
            case 0 :
                tripleString[2] = "0";
                break;
            case 1 :
                tripleString[2] = "1";
                break;
            case 2 :
                tripleString[2] = "2";
                break;
            case 3 :
                tripleString[2] = "3";
                break;
            case 4 :
                tripleString[2] = "4";
                break;
            case 5 :
                tripleString[2] = "5";
                break;
            case 6 :
                tripleString[2] = "6";
                break;
            case 7 :
                tripleString[2] = "7";
                break;
            case 8 :
                tripleString[2] = "8";
                break;
            case 9 :
                tripleString[2] = "9";
                break;
            default :
                tripleString[2] = "-";
        }
        return tripleString;
    }
}

